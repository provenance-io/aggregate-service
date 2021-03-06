package io.provenance.aggregate.common.aws

import io.provenance.aggregate.common.DynamoConfig
import io.provenance.aggregate.common.S3Config
import io.provenance.aggregate.common.aws.s3.client.DefaultS3Client
import io.provenance.aggregate.common.aws.s3.client.S3Client
import io.provenance.aggregate.common.logger
import io.provenance.eventstream.config.Environment
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import kotlin.time.ExperimentalTime
import java.time.Duration as JavaDuration

/**
 * The base class to interact with AWS.
 *
 * @property s3Config The S3 configuration.
 * @property dynamoConfig The DynamoDB configuration.
 * @property region The AWS region to use. If omitted [Region.US_EAST_1] will be used.
 */
@OptIn(ExperimentalTime::class)
abstract class AwsClient(val s3Config: S3Config, val region: String? = null) {

    companion object {
        val DEFAULT_REGION: Region = Region.US_EAST_1

        fun create(environment: Environment, s3Config: S3Config): AwsClient {
            return when (environment) {
                Environment.local -> LocalStackAwsClient(s3Config)
                Environment.development -> DefaultAwsClient(s3Config)
                Environment.production -> DefaultAwsClient(s3Config)
            }
        }
    }

    private val log = logger()

    /**
     * Setting: S3 write timeout.
     *
     * @see https://docs.aws.amazon.com/sdk-for-net/v3/developer-guide/retries-timeouts.html
     *
     * @return The AWS S3 write timeout value.
     */
    protected open fun getS3WriteTimeout(): JavaDuration = JavaDuration.ZERO

    /**
     * Setting: S3 maximum request concurrency level.
     *
     * @see https://docs.aws.amazon.com/sdkref/latest/guide/setting-s3-max_concurrent_requests.html
     *
     * @return The AWS S3 request concurrency value.
     */
    protected open fun getS3MaxConcurrency(): Int = 64

    protected open fun getRegion(): Region = region?.let { Region.of(it) } ?: DEFAULT_REGION

    protected open fun getCredentialsProvider(): AwsCredentialsProvider = DefaultCredentialsProvider.create()

    /**
     * Set connection acquisition timeout
     */
    protected fun getAcquisitionTimeout(): JavaDuration = JavaDuration.ofSeconds(60)

    /**
     * Overrides the default endpoint used by the AWS SDK. This is useful when using something like LocalStack which
     * emulates various AWS services running locally.
     */
    protected open fun getEndpointOverride(): URI? = null

    val s3Client: S3AsyncClient by lazy {
        createS3Client()
    }

    private fun createNettyClient(): SdkAsyncHttpClient {
        return NettyNioAsyncHttpClient.builder()
            .writeTimeout(getS3WriteTimeout())
            .connectionAcquisitionTimeout(getAcquisitionTimeout())
            .maxConcurrency(getS3MaxConcurrency())
            .build()
    }

    private fun createS3Client(): S3AsyncClient {
        val override = getEndpointOverride()
        val httpClient = createNettyClient()
        return S3AsyncClient.builder()
            .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
            .region(getRegion())
            .httpClient(httpClient)
            .serviceConfiguration(
                S3Configuration.builder()
                    .checksumValidationEnabled(false)
                    .chunkedEncodingEnabled(true)
                    // See https://github.com/localstack/localstack/blob/master/README.md#troubleshooting
                    .pathStyleAccessEnabled(true)
                    .build()
            )
            .apply {
                if (override != null) {
                    log.info("Using endpoint override: $override")
                    endpointOverride(override)
                }
            }
            .build()
    }

    /**
     * Returns a client for interacting with AWS S3.
     */
    open fun s3(): S3Client {
        return DefaultS3Client(s3Client, s3Config.bucket)
    }

}
