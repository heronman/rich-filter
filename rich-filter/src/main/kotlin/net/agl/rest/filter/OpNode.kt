package net.agl.rest.filter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode

class OpNode(
    val field: String,
    val op: String,
    val value: Any?,
    val caseSensitive: CaseSensitivity = CaseSensitivity.SENSITIVE,
    val nullsOrder: NullsOrder = NullsOrder.NO_ORDER
) : FilterNode {
    companion object {
        val VALUE_NODE_KEYS = setOf("field", "op", "value", "CS", "NF")

        val COLLECTION_OPS = setOf("in", "nin")
        val EQUALITY_OPS = setOf("eq", "ne", "like", "nlike")
        val COMPARISON_OPS = setOf("gt", "ge", "lt", "le")
        val VALID_OPS = EQUALITY_OPS + COLLECTION_OPS + COMPARISON_OPS

        fun isValueObject(node: JsonNode): Boolean = node.isObject &&
                node.fieldNames().asSequence().all { it in VALUE_NODE_KEYS } &&
                node.has("value")

        fun isValuesArray(node: JsonNode): Boolean =
            node.isArray && (node as ArrayNode).elements().asSequence()
                .all { FilterFlag.isPureFlag(it) || it.isValueNode && !it.isNull }

        fun fromNode(
            node: JsonNode,
            field: String? = null, // field name if already set
            op: String? = null, // operator name if already set
            caseSensitive: CaseSensitivity = CaseSensitivity.SENSITIVE,
            nullsFirst: NullsOrder = NullsOrder.NO_ORDER,
            path: List<String> = listOf()
        ): OpNode {
            if (node.has("field") && field != null) throw OverridingFieldNameException(field, node, path)
            if (!node.has("field") && field == null) throw MissingFieldNameException(node, path)
            if (node.has("op") && op != null) throw OverridingOpNameException(op, node, path)
            if (!node.has("op") && op == null) throw MissingOpNameException(node, path)

            val (case, nulls) = FilterFlag.fromNode(node, caseSensitive, nullsFirst)

            val field = field ?: node["field"].asText()
            val op = op ?: node["op"].asText().also {
                if (it !in VALID_OPS) throw InvalidOperatorException(it, node, path)
            }

            val value = try {
                when {
                    op in COLLECTION_OPS -> when {
                        isValueObject(node) -> extractArrayValues(node["value"])
                        else -> extractArrayValues(node)
                    }

                    else -> when {
                        isValueObject(node) -> extractValue(node["value"])
                        node.isValueNode -> extractValue(node)
                        else -> throw InvalidValueNodeException(node, path)
                    }
                }
            } catch (e: IllegalStateException) {
                if (e is FilterParsingException) throw e
                throw InvalidValueNodeException(node, path, e.message)
            }

            return OpNode(field, op, value, case, nulls)
        }

        private fun extractValue(node: JsonNode): Any? = when {
            node.isContainerNode -> error("Plain value or null is expected, got: $node")
            node.isNull || node.isMissingNode -> null
            node.isBoolean -> node.asBoolean()
            node.isDouble -> node.asDouble()
            node.isFloat -> node.asDouble().toFloat()
            node.isLong -> node.asLong()
            node.isInt -> node.asInt()
            node.isShort -> node.asInt().toShort()
            node.isTextual -> node.asText()
            else -> node.asText()
        }

        private fun extractArrayValues(node: JsonNode): List<Any?> = when {
            node.isArray -> node.map(::extractValue)
            else -> error("An array node is expected, got: $node")
        }
    }

    override fun toString(): String {
        val nullsOrder = if (op in COMPARISON_OPS) nullsOrder.shortName else null
        val caseSensitive = if (value is String) caseSensitive.shortName else null

        val sb = StringBuilder().append('`').append(field).append("` ").append(op)
        if (caseSensitive != null || nullsOrder != null) {
            sb.append("(")
            if (caseSensitive != null) {
                sb.append(caseSensitive)
                nullsOrder?.let { sb.append(",") }
            }
            nullsOrder?.let { sb.append(it) }
            sb.append(")")
        }
        if (value is Collection<*>) sb.append(value.map { "'$it'" })
        else sb.append(" '").append(value).append('\'')
        return sb.toString()
    }
}
