package io.provenance.aggregate.service.aws.dynamodb

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbImmutable
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey

/**
 * Documentation for the enhanced DynamoDB "enhanced" async client:
 * @see https://github.com/aws/aws-sdk-java-v2/tree/master/services-custom/dynamodb-enhanced
 *
 * Resources for Working with immutable data:
 * @see https://github.com/aws/aws-sdk-java-v2/issues/2096#issuecomment-752667521
 */
@DynamoDbImmutable(builder = BlockStorageMetadata.Builder::class)
class BlockStorageMetadata(
    @get:DynamoDbAttribute(value = "BlockHeight")  // AWS conventions dictate upper-case camelCase
    @get:DynamoDbPartitionKey val blockHeight: Long,
    val batchId: String
) {
    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder {
        private var blockHeight: Long? = null
        private var batchId: String? = null

        fun blockHeight(value: Long) = apply { blockHeight = value }
        fun batchId(value: String) = apply { batchId = value }

        fun build() = BlockStorageMetadata(
            blockHeight = blockHeight ?: error("required block height not set"),
            batchId = batchId ?: error("required batch ID not set")
        )
    }
}
