package io.provenance.aggregate.service.stream.extractors.csv.impl

import io.provenance.aggregate.common.extensions.toISOString
import io.provenance.aggregate.common.models.Constants
import io.provenance.aggregate.service.stream.extractors.csv.CSVFileExtractor
import io.provenance.aggregate.common.models.StreamBlock
import io.provenance.aggregate.service.stream.models.provenance.marker.EventMarker
import io.provenance.aggregate.service.stream.repository.db.DBInterface

data class MarkerSupplyDB(
    val event_type: String?,
    val block_height: Long?,
    val block_timestamp: String?,
    val coins: String?,
    val denom: String?,
    val amount: String?,
    val administrator: String?,
    val to_address: String?,
    val from_address: String?,
    val metadata_base: String?,
    val metadata_description: String?,
    val metadata_display: String?,
    val metadata_denom_units: String?,
    val metadata_name: String?,
    val metadata_symbol: String?,
    val fee: Long?,
    val fee_denom: String? = Constants.FEE_DENOMINATION
)

/**
 * Extract data related to the overall supply of a marker.
 */
class TxMarkerSupply : CSVFileExtractor(
    name = "tx_marker_supply",
    headers = listOf(
        "hash",
        "event_type",
        "block_height",
        "block_timestamp",
        "coins",
        "denom",
        "amount",
        "administrator",
        "to_address",
        "from_address",
        "metadata_base",
        "metadata_description",
        "metadata_display",
        "metadata_denom_units",
        "metadata_name",
        "metadata_symbol",
        "fee",
        "fee_denom"
    )
) {
    override suspend fun extract(block: StreamBlock, dbRepository: DBInterface<Any>) {
        for (event in block.txEvents) {
            EventMarker.mapper.fromEvent(event)
                ?.toEventRecord()
                ?.let { record ->
                    // All transfers are processed by `TxMarkerTransfer`
                    if (!record.isTransfer()) {
                        val markerSupplyData = MarkerSupplyDB(
                            event.eventType,
                            event.blockHeight,
                            event.blockDateTime?.toISOString(),
                            record.coins,
                            record.denom,
                            record.amount,
                            record.administrator,
                            record.toAddress,
                            record.fromAddress,
                            record.metadataBase,
                            record.metadataDescription,
                            record.metadataDisplay,
                            record.metadataDenomUnits,
                            record.metadataName,
                            record.metadataSymbol,
                            event.fee,
                            event.feeDenom
                        )
                        syncWriteRecord(
                            markerSupplyData,
                            includeHash = true
                        ).also { hash ->
                            dbRepository.save(hash = hash, markerSupplyData)
                        }
                    }
                }
        }
    }
}
