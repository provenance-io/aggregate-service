package io.provenance.aggregate.repository.model

import io.provenance.aggregate.common.models.Event

data class TxEvents(
    val txHash: String?,
    val eventType: String?,
    val attributes: List<Event>?,
)
