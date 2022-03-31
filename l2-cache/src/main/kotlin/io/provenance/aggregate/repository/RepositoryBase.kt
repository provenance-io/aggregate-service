package io.provenance.aggregate.repository

import io.provenance.aggregate.common.models.StreamBlock

interface RepositoryBase {
    fun saveBlock(block: StreamBlock)
}
