package net.agl.rest.filter

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

class FilterParser {
    companion object {
        private val log = LoggerFactory.getLogger(FilterParser::class.java)
        private val mapper = ObjectMapper().configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true)

        fun parse(src: String): FilterNode = try {
            parse(mapper.readTree(src))
        } catch (e: JsonParseException) {
            throw FilterParsingException.wrapJsonParserException(src, e)
        }

        fun parse(
            node: JsonNode,
            field: String? = null, // field name if already set
            op: String? = null, // operator name if already set
            caseSensitive: CaseSensitivity = CaseSensitivity.SENSITIVE,
            nullsFirst: NullsOrder = NullsOrder.NO_ORDER,
            path: List<String> = listOf()
        ): FilterNode {
            log.debug("Parsing [${path.joinToString(" -> ")}]")
            val (caseSensitive, nullsFirst) = FilterFlag.fromNode(node, caseSensitive, nullsFirst)

            val nodes = when {
                // root or top-level aggregator (no field/op yet)
                op == null && field == null && node.isContainerNode ->
                    parseList(node, null, caseSensitive, nullsFirst, path)

                // field is actually an aggregator name: {"and": [...]}
                op == null && field in FilterNode.AGGREGATORS ->
                    parseList(node, null, caseSensitive, nullsFirst, path)

                // nested container under a field, not a value node / values array
                op == null && field != null && node.isContainerNode &&
                        !OpNode.isValueNode(node) && !OpNode.isValuesArray(node) ->
                    parseList(node, field, caseSensitive, nullsFirst, path)

                // aggregator used as "op" under a field: {"age": {"and": [...]}}
                op in FilterNode.AGGREGATORS && field != null ->
                    parseList(node, field, caseSensitive, nullsFirst, path)

                else -> listOf()
            }

            return when {
                op == null && field == null && node.isContainerNode -> FilterNode.and(nodes)

                op == null && field != null -> when {
                    field == "or" -> FilterNode.or(nodes)
                    field == "and" -> FilterNode.and(nodes)

                    // implicit "in"
                    OpNode.isValuesArray(node) ->
                        OpNode.fromNode(node, field, "in", caseSensitive, nullsFirst, path)

                    // implicit "equals"
                    !node.isContainerNode ->
                        OpNode.fromNode(node, field, "eq", caseSensitive, nullsFirst, path)

                    OpNode.isValueNode(node) ->
                        OpNode.fromNode(node, field, null, caseSensitive, nullsFirst, path)

                    node.isContainerNode -> FilterNode.and(nodes)

                    else -> throw FilterParsingException(path.joinToString("/"), "Invalid node: $node")
                }

                op != null && field != null -> when (op) {
                    in OpNode.VALID_OPS -> OpNode.fromNode(node, field, op, caseSensitive, nullsFirst, path)
                    "or" -> FilterNode.or(nodes)
                    "and" -> FilterNode.and(nodes)
                    else -> throw FilterParsingException(path.joinToString("/"), "Invalid operator: $op")
                }

                else -> throw FilterParsingException(path.joinToString("/"), "Invalid node: $node")
            }
        }

        private fun parseList(
            node: JsonNode,
            field: String?, // field name if already set
            caseSensitive: CaseSensitivity,
            nullsFirst: NullsOrder,
            path: List<String> = listOf()
        ): List<FilterNode> {
            fun scan(key: String, item: JsonNode) = parse(
                item,
                if (node.isObject && field == null) key else field,
                if (node.isObject && field != null) key else null,
                caseSensitive, nullsFirst, path = path + key
            )

            return when {
                node.isArray -> node
                    .filter { !FilterFlag.isPureFlag(it) }
                    .mapIndexed { i, it -> scan(i.toString(), it) }

                node.isObject -> node.properties()
                    .filter { (k, _) -> k !in FilterFlag.VALID_NAMES }
                    .map { (k, v) -> scan(k, v) }

                else -> throw FilterParsingException(path.joinToString("/"), "A container node is expected, got: $node")
            }
        }

     }
}