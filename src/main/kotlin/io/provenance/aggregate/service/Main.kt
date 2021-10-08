package io.provenance.aggregate.service

import com.sksamuel.hoplite.ConfigLoader
import com.sksamuel.hoplite.EnvironmentVariablesPropertySource
import com.sksamuel.hoplite.PropertySource
import com.sksamuel.hoplite.preprocessor.PropsPreprocessor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.timgroup.statsd.NoOpStatsDClient
import com.timgroup.statsd.NonBlockingStatsDClientBuilder
import com.tinder.scarlet.Scarlet
import com.tinder.scarlet.messageadapter.moshi.MoshiMessageAdapter
import com.tinder.scarlet.websocket.okhttp.newWebSocketFactory
import com.tinder.streamadapter.coroutines.CoroutinesStreamAdapterFactory
import io.provenance.aggregate.service.adapter.json.JSONObjectAdapter
import io.provenance.aggregate.service.aws.AwsInterface
import io.provenance.aggregate.service.aws.dynamodb.NoOpDynamo
import io.provenance.aggregate.service.extensions.recordMaxBlockHeight
import io.provenance.aggregate.service.extensions.repeatDecodeBase64
import io.provenance.aggregate.service.flow.extensions.cancelOnSignal
import io.provenance.aggregate.service.stream.EventStream
import io.provenance.aggregate.service.stream.EventStreamUploader
import io.provenance.aggregate.service.stream.EventStreamViewer
import io.provenance.aggregate.service.stream.TendermintServiceClient
import io.provenance.aggregate.service.stream.models.StreamBlock
import io.provenance.aggregate.service.stream.models.UploadResult
import io.provenance.aggregate.service.stream.models.extensions.dateTime
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import org.slf4j.Logger
import java.net.URI
import java.util.concurrent.TimeUnit

fun configureEventStreamBuilder(websocketUri: String): Scarlet.Builder {
    val node = URI(websocketUri)
    return Scarlet.Builder()
        .webSocketFactory(
            OkHttpClient.Builder()
                .pingInterval(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
                .newWebSocketFactory("${node.scheme}://${node.host}:${node.port}/websocket")
        )
        .addMessageAdapterFactory(MoshiMessageAdapter.Factory())
        .addStreamAdapterFactory(CoroutinesStreamAdapterFactory())
}

/**
 * Installs a shutdown a handler to clean up resources when the returned Channel receives its one (and only) element.
 * This is primary intended to be used to clean up resources allocated by Flows.
 */
fun installShutdownHook(log: Logger): Channel<Unit> {
    val signal = Channel<Unit>(1)
    Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() = runBlocking {
            log.warn("Sending cancel signal")
            signal.send(Unit)
            delay(500)
            log.warn("Shutting down")
        }
    })
    return signal
}

@OptIn(FlowPreview::class)
@ExperimentalCoroutinesApi
fun main(args: Array<String>) {
    /**
     * All configuration options can be overridden via environment variables:
     *
     * - To override nested configuration options separated with a dot ("."), use double underscores ("__")
     *   in the environment variable:
     *     event.stream.rpc_uri=http://localhost:26657 is overridden by "event__stream_rpc_uri=foo"
     *
     * @see https://github.com/sksamuel/hoplite#environmentvariablespropertysource
     */
    val parser = ArgParser("aggregate-service")
    val envFlag by parser.option(
        ArgType.Choice<Environment>(),
        shortName = "e",
        fullName = "env",
        description = "Specify the application environment. If not present, fall back to the `\$ENVIRONMENT` envvar",
    )
    val fromHeight by parser.option(
        ArgType.Int,
        fullName = "from",
        description = "Fetch blocks starting from height, inclusive."
    )
    val toHeight by parser.option(
        ArgType.Int, fullName = "to", description = "Fetch blocks up to height, inclusive"
    )
    val observe by parser.option(
        ArgType.Boolean, fullName = "observe", description = "Observe blocks instead of upload"
    ).default(false)
    val restart by parser.option(
        ArgType.Boolean,
        fullName = "restart",
        description = "Restart processing blocks from the last maximum historical block height recorded"
    ).default(false)
    val verbose by parser.option(
        ArgType.Boolean, shortName = "v", fullName = "verbose", description = "Enables verbose output"
    ).default(false)
    val skipIfEmpty by parser.option(
        ArgType.Choice(listOf(false, true), { it.toBooleanStrict() }),
        fullName = "skip-if-empty",
        description = "Skip blocks that have no transactions"
    ).default(true)
    val skipIfSeen by parser.option(
        ArgType.Choice(listOf(false, true), { it.toBooleanStrict() }),
        fullName = "skip-if-seen",
        description = "Skip blocks that have already been seen (stored in DynamoDB)"
    ).default(true)
    val ddHostFlag by parser.option(
        ArgType.String, fullName = "dd-host", description = "Datadog agent metrics will be sent to"
    )
    val ddTagsFlag by parser.option(
        ArgType.String, fullName = "dd-tags", description = "Datadog tags that will be sent with every metric"
    )

    parser.parse(args)

    val ddEnabled = runCatching { System.getenv("DD_ENABLED") }.getOrNull() == "true"
    val ddHost = ddHostFlag ?: runCatching { System.getenv("DD_AGENT_HOST") }.getOrElse { "localhost" }
    val ddTags = ddTagsFlag ?: runCatching { System.getenv("DD_TAGS") }.getOrElse { "" }

    val environment: Environment =
        envFlag ?: runCatching { Environment.valueOf(System.getenv("ENVIRONMENT")) }
            .getOrElse {
                error("Not a valid environment: ${System.getenv("ENVIRONMENT")}")
            }

    val config: Config = ConfigLoader.Builder()
        .addSource(EnvironmentVariablesPropertySource(useUnderscoresAsSeparator = true, allowUppercaseNames = true))
        .apply {
            // If in the local environment, override the ${...} envvar values in `application.properties` with
            // the values provided in the local-specific `local.env.properties` property file:
            if (environment.isLocal()) {
                addPreprocessor(PropsPreprocessor("/local.env.properties"))
            }
        }
        .addSource(PropertySource.resource("/application.yml"))
        .build()
        .loadConfigOrThrow()

    val log = "main".logger()

    val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .add(JSONObjectAdapter())
        .build()
    val wsStreamBuilder = configureEventStreamBuilder(config.eventStream.websocket.uri)
    val tendermintService = TendermintServiceClient(config.eventStream.rpc.uri)
    val aws: AwsInterface = AwsInterface.create(environment, config.aws.s3, config.aws.dynamodb)
    val dynamo = aws.dynamo()
    val dogStatsClient = if (ddEnabled) {
        log.info("Initializing Datadog client...")
        NonBlockingStatsDClientBuilder()
            .prefix("aggregate-service")
            .hostname(ddHost)
            .timeout(5_000)
            .enableTelemetry(false)
            .constantTags(*ddTags.split(" ").toTypedArray())
            .build()
    } else {
        log.info("Datadog client disabled.")
        NoOpStatsDClient()
    }

    val signal: Channel<Unit> = installShutdownHook(log)

    runBlocking(Dispatchers.IO) {

        log.info(
            """
            |run options => {
            |    restart = $restart
            |    from-height = $fromHeight 
            |    to-height = $toHeight
            |    skip-if-empty = $skipIfEmpty
            |    skip-if-seen = $skipIfSeen
            |    dd-enabled = $ddEnabled
            |    dd-host = $ddHost
            |    dd-tags = $ddTags
            |}
            """.trimMargin("|")
        )

        launch {
            while (true) {
                dynamo.getMaxHistoricalBlockHeight()
                    .also { log.info("Maximum block height: ${it ?: "--"}") }
                    ?.let(dogStatsClient::recordMaxBlockHeight)
                    ?.getOrElse { log.error("DD metric failure", it) }
                delay(60_000)
            }
        }

        // Try to figure out if there's a maximum historical block height that's been seen already.
        // * If `--restart` is provided, try to start from there.
        // * If no height exists, `--restart` implies that the caller is interested in historical blocks,
        //   so issue a warning and start at 0.
        //
        // Note: `--restart` can be combined with `--from=HEIGHT`. If both are given, the maximum value
        // will be chosen as the starting height

        val maxHistoricalHeight: Long? = dynamo.getMaxHistoricalBlockHeight()

        log.info("Start :: historical max block height = $maxHistoricalHeight")

        val startFromHeight: Long? = if (restart) {
            if (maxHistoricalHeight == null) {
                log.warn("No historical max block height found; defaulting to 0")
            } else {
                log.info("Restarting from historical max block height: $maxHistoricalHeight")
            }
            maxOf(maxHistoricalHeight ?: 0, fromHeight?.toLong() ?: 0)
        } else {
            fromHeight?.toLong()
        }

        val options = EventStream.Options
            .builder()
            .fromHeight(startFromHeight)
            .toHeight(toHeight?.toLong())
            .skipIfEmpty(skipIfEmpty)
            .skipIfSeen(skipIfSeen)
            .apply {
                if (config.eventStream.filter.txEvents.isNotEmpty()) {
                    matchTxEvent { it in config.eventStream.filter.txEvents }
                }
            }
            .apply {
                if (config.eventStream.filter.blockEvents.isNotEmpty()) {
                    matchBlockEvent { it in config.eventStream.filter.blockEvents }
                }
            }
            .also {
                if (config.eventStream.filter.txEvents.isNotEmpty()) {
                    log.info("Listening for tx events:")
                    for (event in config.eventStream.filter.txEvents) {
                        log.info(" - $event")
                    }
                }
                if (config.eventStream.filter.blockEvents.isNotEmpty()) {
                    log.info("Listening for block events:")
                    for (event in config.eventStream.filter.blockEvents) {
                        log.info(" - $event")
                    }
                }
            }
            .build()

        if (observe) {
            log.info("*** Observing blocks and events. No action will be taken. ***")

            EventStreamViewer(
                EventStream.Factory(config, moshi, wsStreamBuilder, tendermintService, NoOpDynamo()),
                options
            )
                .consume { b: StreamBlock ->
                    val text = "Block: ${b.block.header?.height ?: "--"}:${b.block.header?.dateTime()?.toLocalDate()}"
                    println(
                        if (b.historical) {
                            text
                        } else {
                            green(text)
                        }
                    )
                    if (verbose) {
                        for (event in b.blockEvents) {
                            println("  Block-Event: ${event.eventType}")
                            for (attr in event.attributes) {
                                println("    ${attr.key?.repeatDecodeBase64()}: ${attr.value?.repeatDecodeBase64()}")
                            }
                        }
                        for (event in b.txEvents) {
                            println("  Tx-Event: ${event.eventType}")
                            for (attr in event.attributes) {
                                println("    ${attr.key?.repeatDecodeBase64()}: ${attr.value?.repeatDecodeBase64()}")
                            }
                        }
                    }
                    println()
                }
        } else {
            if (config.upload.extractors.isNotEmpty()) {
                log.info("upload: adding extractors")
                for (event in config.upload.extractors) {
                    log.info(" - $event")
                }
            }

            EventStreamUploader(
                EventStream.Factory(config, moshi, wsStreamBuilder, tendermintService, dynamo),
                aws,
                moshi,
                options
            )
                .addExtractor(config.upload.extractors)
                .upload()
                .cancelOnSignal(signal)
                .collect { result: UploadResult ->
                    println("uploaded #${result.batchId} => S3 ETag: ${result.eTag}")
                }
        }
    }
}
