package io.provenance.aggregate.service

import cloud.localstack.ServiceName
import cloud.localstack.docker.LocalstackDockerExtension
import cloud.localstack.docker.annotation.LocalstackDockerProperties
import io.provenance.aggregate.service.base.TestBase
import io.provenance.aggregate.service.mocks.*
import io.provenance.aggregate.service.stream.EventStream
import io.provenance.aggregate.service.stream.EventStreamUploader
import io.provenance.aggregate.service.stream.models.StreamBlock
import io.provenance.aggregate.service.stream.models.UploadResult
import io.provenance.aggregate.service.utils.Builders
import io.provenance.aggregate.service.utils.Defaults
import io.provenance.aggregate.service.utils.EXPECTED_NONEMPTY_BLOCKS
import io.provenance.aggregate.service.utils.MIN_BLOCK_HEIGHT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.junitpioneer.jupiter.SetEnvironmentVariable
import org.junitpioneer.jupiter.SetEnvironmentVariable.SetEnvironmentVariables
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalCoroutinesApi
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(LocalstackDockerExtension::class)
@LocalstackDockerProperties(services = [ServiceName.S3, ServiceName.DYNAMO])
class AWSTests : TestBase() {

    private val aws: MockAwsInterface = MockAwsInterface.builder()
        .build(Defaults.s3Config, Defaults.dynamoConfig)

    // Get a view of the AWS S3 interface with more stuff on it needed during testing
    val s3: LocalStackS3 = aws.s3() as LocalStackS3

    // Get a view of the AWS S3 interface with more stuff on it needed during testing
    val dynamo: LocalStackDynamo = aws.dynamo() as LocalStackDynamo

    @BeforeAll
    override fun setup() {
        super.setup()
    }

    @AfterAll
    override fun tearDown() {
        super.tearDown()
    }

    @BeforeEach
    fun setupBeforeEach() {
        // TODO: Change this to runBlockingTest when issues are fixed. See https://github.com/Kotlin/kotlinx.coroutines/issues/1204
        // When run with runBlockingTest: `java.lang.IllegalStateException: This job has not completed yet` will be
        // thrown
        // NOTE! This is possibly a problem when using `.await()` with `CompleteableFuture`, as that is needed when using
        // the AWS SDK v2 async clients
        runBlocking(dispatcherProvider.main()) {
            s3.createBucket()
            dynamo.createTable()
        }
    }

    @AfterEach
    fun teardownAfterEach() {
        // TODO: Change this to runBlockingTest when issues are fixed. See https://github.com/Kotlin/kotlinx.coroutines/issues/1204
        runBlocking(dispatcherProvider.main()) {
            s3.emptyAndDeleteBucket()
            dynamo.dropTable()
        }
    }

    private suspend fun createSimpleEventStream(
        includeLiveBlocks: Boolean = true,
        skipIfEmpty: Boolean = true,
        skipIfSeen: Boolean = true
    ): EventStream {
        val eventStreamService: MockEventStreamService =
            Builders.eventStreamService(includeLiveBlocks = includeLiveBlocks)
                .dispatchers(dispatcherProvider)
                .build()

        val tendermintService: MockTendermintService = Builders.tendermintService()
            .build(MockTendermintService::class.java)

        return Builders.eventStream()
            .eventStreamService(eventStreamService)
            .tendermintService(tendermintService)
            .dynamoInterface(dynamo)  // use LocalStack's Dynamo instance:
            .dispatchers(dispatcherProvider)
            .fromHeight(MIN_BLOCK_HEIGHT)
            .skipIfEmpty(skipIfEmpty)
            .skipIfSeen(skipIfSeen)
            .build()
    }

    @OptIn(FlowPreview::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)
    @Test
    @SetEnvironmentVariables(
        SetEnvironmentVariable(
            key = "AWS_ACCESS_KEY_ID",
            value = "test",
        ),
        SetEnvironmentVariable(
            key = "AWS_SECRET_ACCESS_KEY",
            value = "test"
        )
    )
    fun testSimpleStreamBlocksToS3() {
        // TODO: Use this when https://github.com/Kotlin/kotlinx.coroutines/issues/1204 is fixed
        // NOTE! This is possibly a problem when using `.await()` with `CompleteableFuture`, as that is needed when using
        // the AWS SDK v2 async clients
        runBlocking(dispatcherProvider.main()) {

            val eventStreamService = Builders.eventStreamService(includeLiveBlocks = true)
                .dispatchers(dispatcherProvider)
                .build()

            val tendermintService = Builders.tendermintService()
                .build(MockTendermintService::class.java)

            val expectedTotal: Long = EXPECTED_NONEMPTY_BLOCKS + eventStreamService.expectedResponseCount()

            val stream = Builders.eventStream()
                .eventStreamService(eventStreamService)
                .tendermintService(tendermintService)
                .dynamoInterface(dynamo)
                .dispatchers(dispatcherProvider)
                .fromHeight(MIN_BLOCK_HEIGHT)
                .skipIfEmpty(true)
                .build()

            val uploadResults: List<UploadResult>? = withTimeoutOrNull(Duration.seconds(10)) {
                EventStreamUploader(
                    stream,
                    aws,
                    Defaults.moshi,
                    EventStream.Options.DEFAULT,
                    dispatchers = dispatcherProvider
                )
                    .upload()
                    .toList()
            }

            assert(uploadResults != null && uploadResults.isNotEmpty())
            assert(uploadResults?.sumOf { it.batchSize } ?: 0 == expectedTotal.toInt()) {
                "EventStreamUploader: Collection timed out (probably waiting for more live blocks that aren't coming)"
            }

            // check S3 and make sure there's <expectTotal> objects in the bucket:
            val keys = s3.listBucketObjectKeys()
            assert(keys.isNotEmpty() && keys.size == uploadResults?.size ?: 0)
        }
    }

    @OptIn(FlowPreview::class, ExperimentalTime::class, ExperimentalCoroutinesApi::class)
    @Test
    @SetEnvironmentVariables(
        SetEnvironmentVariable(
            key = "AWS_ACCESS_KEY_ID",
            value = "test",
        ),
        SetEnvironmentVariable(
            key = "AWS_SECRET_ACCESS_KEY",
            value = "test"
        )
    )
    fun testHandlingPreviouslySeenBlocks() {

        runBlocking(dispatcherProvider.main()) {

            // === (CASE 1 -- Never seen) ==============================================================================
            var inspected1 = false
            val uploadResults1: List<UploadResult>? = withTimeoutOrNull(Duration.seconds(10)) {
                EventStreamUploader(
                    createSimpleEventStream(includeLiveBlocks = true, skipIfEmpty = true, skipIfSeen = false),
                    aws,
                    Defaults.moshi,
                    EventStream.Options.DEFAULT,
                    dispatchers = dispatcherProvider
                )
                    .upload { block ->
                        // Inspect each block
                        inspected1 = true
                        // There should be no storage metadata attached to any of the blocks because they haven't been
                        // seen yet. Both historical and live blocks won't have any metadata.
                        assert(block.metadata == null)
                    }
                    .toList()
            }
            assert(uploadResults1 != null && uploadResults1.isNotEmpty()) { "Stream (1) failed to collect in time" }
            assert(inspected1) { "Stream 1: no blocks emitted" }

            // === (CASE 2 -- Seen and skipped ) =======================================================================

            // Re-run on a different instance of the stream that's using the same data:
            val blocks2 = mutableListOf<StreamBlock>()
            val uploadResults2: List<UploadResult>? = withTimeoutOrNull(Duration.seconds(10)) {
                EventStreamUploader(
                    createSimpleEventStream(includeLiveBlocks = true, skipIfEmpty = true, skipIfSeen = true),
                    aws,
                    Defaults.moshi,
                    EventStream.Options.DEFAULT,
                    dispatchers = dispatcherProvider
                )
                    .upload { block -> blocks2.add(block) }
                    .toList()
            }
            assert(uploadResults2 != null && uploadResults2.isNotEmpty()) { "Stream (2) failed to collect in time" }
            assert(blocks2.isNotEmpty()) { "Stream (2): no blocks emitted" }
            assert(blocks2.none { it.historical && it.metadata != null }) { "Stream (2) : historical blocks not empty" }
            // "Live" technically haven't been seen, so they will always appear, even if `skipIfSeen` = true
            assert(blocks2.any { !it.historical && it.metadata == null }) { "Stream (2) : live blocks empty" }

            // === (CASE 3 -- Seen and not skipped) ====================================================================

            // Re-run for a third time, but don't skip seen blocks. The returned stream blocks should all have a
            // `BlockStorageMetadata` value, since they've been tracked in Dynamo:
            val blocks3 = mutableListOf<StreamBlock>()
            val uploadResults3: List<UploadResult>? = withTimeoutOrNull(Duration.seconds(10)) {
                EventStreamUploader(
                    createSimpleEventStream(includeLiveBlocks = true, skipIfEmpty = true, skipIfSeen = false),
                    aws,
                    Defaults.moshi,
                    EventStream.Options.DEFAULT,
                    dispatchers = dispatcherProvider
                )
                    .upload { block -> blocks3.add(block) }
                    .toList()
            }
            assert(uploadResults3 != null && uploadResults3.isNotEmpty()) { "Stream (3) failed to collect in time" }
            assert(blocks3.isNotEmpty()) { "Stream (3): no blocks emitted" }
            assert(uploadResults3!!.size == uploadResults1!!.size) { "Stream (3) expected to be the same length as stream (1)" }

            val historicalBlocks = blocks3.filter { it.historical }
            val liveBlocks = blocks3.filter { !it.historical }

            assert(historicalBlocks.isNotEmpty()) { "Stream (3) : historical blocks empty" }
            assert(liveBlocks.isNotEmpty()) { "Stream (3) : live blocks empty" }

            // Historical blocks should have a Dynamo storage metadata entry:
            assert(historicalBlocks.all { it.metadata != null }) { "Stream (3) historical blocks should all have metadata " }
            assert(liveBlocks.all { it.metadata == null }) { "Stream (3) live blocks should not have any metadata " }
        }
    }
}
