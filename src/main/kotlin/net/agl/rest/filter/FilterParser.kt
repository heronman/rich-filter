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
            ctx: ParseContext = ParseContext.Root,
            cs: CaseSensitivity = CaseSensitivity.SENSITIVE,
            nf: NullsOrder = NullsOrder.NO_ORDER,
            path: List<String> = listOf()
        ): FilterNode {
            log.debug("Parsing [${path.joinToString(" -> ")}], ctx=$ctx, node type: ${node.nodeType}")

            val (caseSensitive, nullsFirst) = try {
                FilterFlag.fromNode(node, cs, nf)
            } catch (e: IllegalStateException) {
                throw InvalidNodeException(node, path, e.message)
            }

            return when (ctx) {
                is ParseContext.Root -> parseRoot(node, caseSensitive, nullsFirst, path)
                is ParseContext.Field -> parseField(node, ctx.name, caseSensitive, nullsFirst, path)
                is ParseContext.Op -> parseOp(node, ctx.field, ctx.op, caseSensitive, nullsFirst, path)
                is ParseContext.Agg -> parseAgg(node, ctx, caseSensitive, nullsFirst, path)
            }
        }

        private fun parseRoot(node: JsonNode, cs: CaseSensitivity, nf: NullsOrder, path: List<String>): FilterNode =
            when {
                node.isArray -> parseAgg(node, ParseContext.Agg("and", field = null), cs, nf, path)

                node.isObject -> FilterNode.and(
                    node.properties()
                        .filter { (k, _) -> k !in FilterFlag.VALID_NAMES }
                        .map { (k, v) ->
                            when (k) {
                                in FilterNode.AGGREGATORS ->
                                    parse(v, ParseContext.Agg(k, null), cs, nf, path + k)

                                else ->
                                    parse(v, ParseContext.Field(k), cs, nf, path + k)
                            }
                        }
                )

                else -> throw NonContainerNodeException(node, path)
            }

        private fun parseField(
            node: JsonNode,
            field: String,
            cs: CaseSensitivity,
            nf: NullsOrder,
            path: List<String>
        ): FilterNode = when {
            OpNode.isValuesArray(node) -> OpNode.fromNode(node, field, "in", cs, nf, path)

            OpNode.isValueObject(node) -> OpNode.fromNode(node, field, null, cs, nf, path)

            node.isObject -> FilterNode.and(
                node.properties()
                    .filter { (k, _) -> k !in FilterFlag.VALID_NAMES }
                    .map { (k, v) ->
                        when {
                            k in OpNode.VALID_OPS ->
                                parse(v, ParseContext.Op(field, k), cs, nf, path + k)

                            k in FilterNode.AGGREGATORS ->
                                parse(v, ParseContext.Agg(k, field), cs, nf, path + k)

                            else -> throw InvalidOperatorException(k, node, path)
                        }
                    }
            )

            node.isArray -> FilterNode.and(
                node.filter { !FilterFlag.isPureFlag(it) }
                    .mapIndexed { i, v -> parse(v, ParseContext.Field(field), cs, nf, path + i.toString()) }
            )

            else -> OpNode.fromNode(node, field, "eq", cs, nf, path)
        }

        private fun parseOp(
            node: JsonNode,
            field: String,
            op: String,
            cs: CaseSensitivity,
            nf: NullsOrder,
            path: List<String>
        ): FilterNode = OpNode.fromNode(node, field, op, cs, nf, path)

        private fun parseAgg(
            node: JsonNode,
            ctx: ParseContext.Agg,
            cs: CaseSensitivity,
            nf: NullsOrder,
            path: List<String>
        ): FilterNode {
            val kind = ctx.kind
            val field = ctx.field

            if (node.isValueNode) throw NonContainerNodeException(node, path)

            val children: List<FilterNode> = when {
                node.isArray -> node.filter { !FilterFlag.isPureFlag(it) }
                    .mapIndexed { i, child ->
                        when {
                            field == null && child.isObject -> {
                                val (k, v) = child.properties().first()
                                when {
                                    k in FilterNode.AGGREGATORS -> parse(
                                        v,
                                        ParseContext.Agg(k, null),
                                        cs,
                                        nf,
                                        path + i.toString()
                                    )

                                    else -> parse(v, ParseContext.Field(k), cs, nf, path + i.toString())
                                }
                            }

                            field != null -> parse(child, ParseContext.Field(field), cs, nf, path + i.toString())

                            else -> throw InvalidNodeException(child, path + i.toString())
                        }
                    }

                node.isObject -> {
                    node.properties()
                        .filter { (k, _) -> k !in FilterFlag.VALID_NAMES }
                        .map { (k, v) ->
                            when {
                                field == null && k in FilterNode.AGGREGATORS -> parse(
                                    v,
                                    ParseContext.Agg(k, null),
                                    cs,
                                    nf,
                                    path + k
                                )

                                field == null ->
                                    parse(v, ParseContext.Field(k), cs, nf, path + k)

                                else ->
                                    parse(v, ParseContext.Field(field), cs, nf, path + k)
                            }
                        }
                }

                else -> emptyList()
            }

            return when (kind) {
                "and" -> FilterNode.and(children)
                "or" -> FilterNode.or(children)
                else -> throw InvalidOperatorException(kind, node, path)
            }
        }

    }
}
