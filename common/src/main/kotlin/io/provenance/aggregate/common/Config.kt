package io.provenance.aggregate.common

import com.sksamuel.hoplite.ConfigAlias
import io.provenance.aggregate.common.aws.s3.S3Bucket

data class S3Config(
    val bucket: S3Bucket
)

data class DynamoConfig(
    val region: String?,
    @ConfigAlias("service_metadata_table") val serviceMetadataTable: DynamoTable,
    @ConfigAlias("block_batch_table") val blockBatchTable: DynamoTable,
    @ConfigAlias("block_metadata_table") val blockMetadataTable: DynamoTable,
    val dynamoBatchGetItems: Long
)

data class AwsConfig(
    val region: String?,
    val s3: S3Config
)

data class UploadConfig(
    val extractors: List<String> = emptyList()
) {
    companion object {
        fun empty() = UploadConfig()
    }
}

data class Config (
    val aws: AwsConfig,
    val wsNode: String,
    val hrp: String,
    val upload: UploadConfig = UploadConfig.empty(),
    val dbConfig: DBConfig
)

data class DBConfig(
    val addr: String,
    val dbName: String,
    val cacheTable: String,
    val dbMaxConnections: Int,
    val dbType: DbTypes,
    val dynamodb: DynamoConfig
)
