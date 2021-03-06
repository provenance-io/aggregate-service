package io.provenance.aggregate.service.test.utils

import com.squareup.moshi.Moshi
import com.tinder.scarlet.lifecycle.LifecycleRegistry
import io.provenance.aggregate.common.aws.AwsClient
import io.provenance.aggregate.service.test.mocks.MockAwsClient
import io.provenance.aggregate.service.test.mocks.MockEventStreamService
import io.provenance.aggregate.service.test.mocks.MockTendermintServiceClient
import io.provenance.aggregate.service.test.mocks.ServiceMocker
import io.provenance.eventstream.adapter.json.decoder.MoshiDecoderEngine
import io.provenance.eventstream.coroutines.DispatcherProvider
import io.provenance.eventstream.defaultWebSocketChannel
import io.provenance.eventstream.stream.BlockStreamOptions
import io.provenance.eventstream.stream.EventStream
import io.provenance.eventstream.stream.TendermintServiceClient
import io.provenance.eventstream.stream.WebSocketService
import io.provenance.eventstream.stream.clients.TendermintBlockFetcher
import io.provenance.eventstream.stream.models.ABCIInfoResponse
import io.provenance.eventstream.stream.models.BlockResponse
import io.provenance.eventstream.stream.models.BlockResultsResponse
import io.provenance.eventstream.stream.models.BlockchainResponse
import io.provenance.eventstream.stream.withLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@ExperimentalCoroutinesApi
object Builders {

    /**
     * Create a mock of the Tendermint service API exposed on Provenance.
     */
    fun tendermintService(): ServiceMocker.Builder = ServiceMocker.Builder()
        .doFor("abciInfo") {
            Defaults.templates.readAs(
                ABCIInfoResponse::class.java,
                "abci_info/success.json",
                mapOf("last_block_height" to MAX_HISTORICAL_BLOCK_HEIGHT)
            )
        }
        .doFor("block") { Defaults.templates.readAs(BlockResponse::class.java, "block/${it[0]}.json") }
        .doFor("blockResults") {
            Defaults.templates.readAs(
                BlockResultsResponse::class.java,
                "block_results/${it[0]}.json"
            )
        }
        .doFor("blockchain") {
            Defaults.templates.readAs(
                BlockchainResponse::class.java,
                "blockchain/${it[0]}-${it[1]}.json"
            )
        }

    /**
     * Create a mock of the Tendermint service API exposed on Provenance with Custom response.
     */
    fun tendermintServiceCustom(customJson: String): ServiceMocker.Builder = ServiceMocker.Builder()
        .doFor("abciInfo") {
            Defaults.templates.readAs(
                ABCIInfoResponse::class.java,
                "abci_info/success.json",
                mapOf("last_block_height" to MAX_HISTORICAL_BLOCK_HEIGHT)
            )
        }
        .doFor("block") { Defaults.templates.readAs(BlockResponse::class.java, "block/${it[0]}.json") }
        .doFor("blockResults") {
            Defaults.templates.readAs(
                BlockResultsResponse::class.java,
                "block_results/${customJson}"
            )
        }
        .doFor("blockchain") {
            Defaults.templates.readAs(
                BlockchainResponse::class.java,
                "blockchain/${customJson}"
            )
        }

    @OptIn(ExperimentalTime::class)
    fun webSocketService(includeLiveBlocks: Boolean = true): MockEventStreamService.Builder {
        val serviceBuilder = MockEventStreamService.builder()
        if(includeLiveBlocks) {
            for(liveBlockResponse in Defaults.templates.readAll("live"))
                serviceBuilder.response(liveBlockResponse)
        }
        return serviceBuilder
    }

    object AwsInterfaceBuilder {
        fun build(): AwsClient = MockAwsClient
            .Builder()
            .build(Defaults.s3Config)
    }

    fun defaultAws(): AwsInterfaceBuilder = AwsInterfaceBuilder

    /**
     * Create a mock of the Provenance block event stream.
     */
    data class EventStreamBuilder(val builders: Builders) {
        var dispatchers: DispatcherProvider? = null
        var webSocketService: WebSocketService? = null
        var tendermintServiceClient: TendermintServiceClient? = null
        var moshi: Moshi? = null
        var options: BlockStreamOptions = BlockStreamOptions()
        var includeLiveBlocks: Boolean = true

        fun <T : WebSocketService> webSocketService(value: T) = apply { webSocketService = value }
        fun <T : TendermintServiceClient> tendermintService(value: T) = apply { tendermintServiceClient = value }
        fun moshi(value: Moshi) = apply { moshi = value }
        fun dispatchers(value: DispatcherProvider) = apply { dispatchers = value }
        fun options(value: BlockStreamOptions) = apply { options = value }
        fun includeLiveBlocks(value: Boolean) = apply { includeLiveBlocks = value }

        // shortcuts for options:
        fun batchSize(value: Int) = apply { options = options.copy(batchSize = value) }
        fun fromHeight(value: Long) = apply { options = options.copy(fromHeight = value) }
        fun toHeight(value: Long) = apply { options = options.copy(toHeight = value) }
        fun skipEmptyBlocks(value: Boolean) = apply { options = options.copy(skipEmptyBlocks = value) }
        fun matchBlockEvents(events: Set<String>) = apply { options = options.copy(blockEvents = events) }
        fun matchTxEvents(events: Set<String>) = apply { options = options.copy(txEvents = events) }

        suspend fun build(): EventStream {
            val dispatchers = dispatchers ?: error("dispatchers must be provided")

            return EventStream(
                eventStreamService = webSocketService
                    ?: builders
                        .webSocketService(includeLiveBlocks = includeLiveBlocks)
                        .dispatchers(dispatchers)
                        .build(),
                fetcher = TendermintBlockFetcher(
                    tendermintServiceClient
                        ?: builders.tendermintService().build(MockTendermintServiceClient::class.java)
                ),
                decoder = MoshiDecoderEngine(moshi!!),
                dispatchers = dispatchers,
                options = options
            )
        }
    }

    fun eventStream(): EventStreamBuilder = EventStreamBuilder(this)
}
