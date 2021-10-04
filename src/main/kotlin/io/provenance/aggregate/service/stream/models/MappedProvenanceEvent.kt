package io.provenance.aggregate.service.stream.models

/**
 * Annotate a class with this to associate it with a Provenance event.
 *
 * @example
 *
 *   @MappedProvenanceEvent("provenance.attribute.v1.EventAttributeAdd")
 *   class AttributeAdd(height: Long, val map: Map<String, Any?>) : ProvenanceTxEvent(height) { ... }
 */
@Target(AnnotationTarget.CLASS)
annotation class MappedProvenanceEvent(val name: String)