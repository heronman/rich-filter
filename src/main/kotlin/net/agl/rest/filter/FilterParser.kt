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

            return when {
                op == null && field == null && (node.isArray || node.isObject) ->
                    parseList(node, null, caseSensitive, nullsFirst, path = path)
                        .let { if (it.size == 1) it.first() else AndNode(it) }

                op == null && field != null -> when {
                    field == "or" -> OrNode(parseList(node, null, caseSensitive, nullsFirst, path = path))
                    field == "and" -> AndNode(parseList(node, null, caseSensitive, nullsFirst, path = path))

                    // implicit "in"
                    OpNode.isValuesArray(node) ->
                        OpNode.fromNode(node, field, "in", caseSensitive, nullsFirst)

                    // implicit "equals"
                    !node.isContainerNode ->
                        OpNode.fromNode(node, field, "eq", caseSensitive, nullsFirst)

                    OpNode.isValueNode(node) ->
                        OpNode.fromNode(node, field, null, caseSensitive, nullsFirst)

                    node.isContainerNode -> parseList(node, field, caseSensitive, nullsFirst, path = path)
                        .let { if (it.size == 1) it.first() else AndNode(it) }

                    else -> error("Invalid node: $node")
                }

                op != null && field != null -> when (op) {
                    in OpNode.VALID_OPS -> OpNode.fromNode(node, field, op, caseSensitive, nullsFirst)
                    "or" -> OrNode(parseList(node, field, caseSensitive, nullsFirst, path = path))
                    "and" -> AndNode(parseList(node, field, caseSensitive, nullsFirst, path = path))
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

            return when {
                node.isArray -> node
                    .filter { !FilterFlag.isPureFlag(it) }
                    .mapIndexed { i, it -> parse(it, field, null, caseSensitive, nullsFirst, path = path + i.toString()) }

                node.isObject -> node.properties()
                    .filter { (k, _) -> k !in FilterFlag.VALID_NAMES }
                    .map { (k, v) ->
                        when (field) {
                            null -> parse(v, k, null, caseSensitive, nullsFirst, path = path + k)
                            else -> parse(v, field, k, caseSensitive, nullsFirst, path = path + k)
                        }
                    }

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