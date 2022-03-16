package io.provenance.aggregate.common.aws.dynamodb.extensions

import io.provenance.aggregate.common.aws.dynamodb.BlockStorageMetadata
import io.provenance.aggregate.common.models.BatchId
import io.provenance.aggregate.common.utils.timestamp
import io.provenance.eventstream.stream.models.StreamBlock

fun StreamBlock.toBlockStorageMetadata(batchId: BatchId): BlockStorageMetadata? =
    this.height
        ?.let { height: Long ->
            BlockStorageMetadata(
                blockHeight = height,
                batchId = batchId.toString(),
                updatedAt = timestamp()
            )
        }
