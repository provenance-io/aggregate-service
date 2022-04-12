package io.provenance.aggregate.repository.model

import io.provenance.aggregate.common.extensions.decodeBase64
import io.provenance.eventstream.stream.models.Event
import io.provenance.eventstream.stream.models.StreamBlock

fun StreamBlock.txHash(index: Int): String? = this.block.data?.txs?.get(index)

fun List<Event>.toEventsData(): List<EventData> =
    this.map { EventData(it.key?.decodeBase64(), it.value?.decodeBase64(), it.index ?: false) }



