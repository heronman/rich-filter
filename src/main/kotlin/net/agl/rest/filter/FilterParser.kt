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

        fun parse(src: String): FilterNode = parse(mapper.readTree(src))

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
            val andOrSingle: (List<FilterNode>) -> FilterNode =
                { nodes -> if (nodes.size == 1) nodes.first() else AndNode(nodes) }

            val nodes = if (
                (op == null && field == null && node.isContainerNode) ||
                (op == null && field != null && field in FilterNode.AGGREGATORS)
            ) parseList(node, null, caseSensitive, nullsFirst, path = path)
            else if (
                (op == null && field != null && node.isContainerNode &&
                        !OpNode.isValueNode(node) && !OpNode.isValuesArray(node)) ||
                (op != null && field != null && op in FilterNode.AGGREGATORS)
            ) parseList(node, field, caseSensitive, nullsFirst, path = path)
            else listOf()

            return when {
                op == null && field == null && node.isContainerNode -> andOrSingle(nodes)

                op == null && field != null -> when {
                    field == "or" -> OrNode(nodes)
                    field == "and" -> AndNode(nodes)

                    // implicit "in"
                    OpNode.isValuesArray(node) ->
                        OpNode.fromNode(node, field, "in", caseSensitive, nullsFirst)

                    // implicit "equals"
                    !node.isContainerNode ->
                        OpNode.fromNode(node, field, "eq", caseSensitive, nullsFirst)

                    OpNode.isValueNode(node) ->
                        OpNode.fromNode(node, field, null, caseSensitive, nullsFirst)

                    node.isContainerNode -> andOrSingle(nodes)

                    else -> error("Invalid node: $node")
                }

                op != null && field != null -> when (op) {
                    in OpNode.VALID_OPS -> OpNode.fromNode(node, field, op, caseSensitive, nullsFirst)
                    "or" -> OrNode(nodes)
                    "and" -> AndNode(nodes)
                    else -> error("Invalid operator: $op")
                }

                else -> error("Invalid node: $node")
            }
        }

        fun parseList(
            node: JsonNode,
            field: String? = null, // field name if already set
            caseSensitive: CaseSensitivity = CaseSensitivity.SENSITIVE,
            nullsFirst: NullsOrder = NullsOrder.NO_ORDER,
            path: List<String> = listOf()
        ): List<FilterNode> {
            val (caseSensitive, nullsFirst) = FilterFlag.fromNode(node, caseSensitive, nullsFirst)

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

                else -> error("A container node is expected, got: $node")
            }

        }

        fun buildJsonParserErrorMessage(src: String?, e: JsonParseException): String {
            val sb = StringBuilder(e.originalMessage)
            sb.append(" at ['").append(e.processor.parsingContext.pathAsPointer()).append("' - ")
            if (src == null || src.count { it == '\n' } > 0) sb.append("line: ${e.location.lineNr}, column: ${e.location.columnNr}")
            else sb.append("character: ${e.location.charOffset}")
            sb.append("]")
            return sb.toString()
        }

    }
}