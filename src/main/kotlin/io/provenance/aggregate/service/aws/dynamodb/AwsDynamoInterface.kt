package io.provenance.aggregate.service.aws.dynamodb

import io.provenance.aggregate.service.stream.batch.BatchId
import io.provenance.aggregate.service.stream.models.StreamBlock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold
import okhttp3.internal.toImmutableMap

interface AwsDynamoInterface {

    suspend fun getBlockMetadata(blockHeight: Long): BlockStorageMetadata?

    suspend fun getBlockMetadata(blockHeights: Iterable<Long>): Flow<BlockStorageMetadata>

    suspend fun getBlockMetadataMap(blockHeights: Iterable<Long>): Map<Long, BlockStorageMetadata> =
        getBlockMetadata(blockHeights).fold(mutableMapOf<Long, BlockStorageMetadata>()) { mapping: MutableMap<Long, BlockStorageMetadata>, metadata: BlockStorageMetadata ->
            mapping[metadata.blockHeight] = metadata
            mapping
        }
            .toImmutableMap()

    suspend fun trackBlocks(batchId: BatchId, blocks: Iterable<StreamBlock>): WriteResult
}