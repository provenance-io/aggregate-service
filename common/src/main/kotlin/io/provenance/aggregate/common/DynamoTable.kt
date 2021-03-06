package io.provenance.aggregate.common

/**
 * A value wrapper around a string that represents a DynamoDB table name.
 *
 * @property name The name of the DynamoDB table.
 */
@JvmInline
value class DynamoTable(val name: String)
