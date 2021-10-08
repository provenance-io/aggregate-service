package io.provenance.aggregate.service.stream.extractors.csv.impl

import io.provenance.aggregate.service.aws.s3.AwsS3Interface
import io.provenance.aggregate.service.extensions.toISOString
import io.provenance.aggregate.service.stream.extractors.csv.CSVFileExtractor
import io.provenance.aggregate.service.stream.models.StreamBlock
import io.provenance.aggregate.service.stream.models.provenance.marker.EventMarker

/**
 * Extract data related to the overall supply of a marker.
 */
class TxMarkerSupply(val s3: AwsS3Interface) : CSVFileExtractor(
    "tx_marker_supply",
    listOf(
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
        "metadata_symbol"
    )
) {
    override suspend fun extract(block: StreamBlock) {
        for (event in block.txEvents) {
            EventMarker.mapper.fromEvent(event)
                ?.toEventRecord()
                ?.let { record ->
                    // All transfers are processed by `TxMarkerTransfer`
                    if (!record.isTransfer()) {
                        syncWriteRecord(
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
                        )
                    }
                }
        }
    }
}
