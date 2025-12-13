package net.agl.rest.filter

import com.fasterxml.jackson.databind.JsonNode

sealed interface FilterFlag {
    companion object {
        val VALID_NAMES = setOf("CS", "NF")

        fun isPureFlag(node: JsonNode): Boolean =
            node.isObject && node.size() == 1 && node.properties().first().key in VALID_NAMES

        fun fromNode(
            node: JsonNode,
            defalutCaseSensitivity: CaseSensitivity = CaseSensitivity.SENSITIVE,
            defaultNullsOrder: NullsOrder = NullsOrder.NO_ORDER
        ): Pair<CaseSensitivity, NullsOrder> {
            when {
                node.isObject -> {
                    val cs = node["CS"].let { CaseSensitivity.fromNode(it) }
                    val nf = node["NF"].let { NullsOrder.fromNode(it) }
                    return (cs ?: defalutCaseSensitivity) to (nf ?: defaultNullsOrder)
                }


                node.isArray -> {
                    val (cs, nf) = node.elements().asSequence()
                        .filter { isPureFlag(it) }
                        .mapNotNull {
                            when {
                                it.has("CS") -> CaseSensitivity.fromNode(it["CS"])
                                it.has("NF") -> NullsOrder.fromNode (it["NF"])
                                else -> null
                            }
                        }
                        .partition { it is CaseSensitivity }

                    if (cs.size > 1) error("Multiple CS flags set at the same level")
                    if (nf.size > 1) error("Multiple NF flags set at the same level")

                    @Suppress("UNCHECKED_CAST")
                    return ((cs.firstOrNull() ?: defalutCaseSensitivity) as CaseSensitivity) to
                            ((nf.firstOrNull() ?: defaultNullsOrder) as NullsOrder)
                }

                else -> return defalutCaseSensitivity to defaultNullsOrder
            }
        }
    }
}

enum class CaseSensitivity(val asBoolean: Boolean, val shortName: String? = null) : FilterFlag {
    SENSITIVE(true),
    INSENSITIVE(false, "CI");

    companion object {
        fun fromBoolean(v: Boolean): CaseSensitivity = if (v) SENSITIVE else INSENSITIVE

        fun fromNode(node: JsonNode?): CaseSensitivity? = when {
            node == null -> null
            node.isBoolean -> CaseSensitivity.fromBoolean(node.asBoolean())
            else -> error("Invalid CS flag: $node")
        }
    }
}

enum class NullsOrder(val asBoolean: Boolean?, val shortName: String? = null) : FilterFlag {
    NULLS_FIRST(true, "NF"),
    NULLS_LAST(false, "NL"),
    NO_ORDER(null);

    companion object {
        fun fromBoolean(v: Boolean?): NullsOrder =
            when (v) {
                true -> NULLS_FIRST
                false -> NULLS_LAST
                null -> NO_ORDER
            }

        fun fromNode(node: JsonNode?): NullsOrder? = when {
            node == null -> null
            node.isNull -> NO_ORDER
            node.isBoolean -> NullsOrder.fromBoolean(node.asBoolean())
            else -> error("Invalid NF flag: $node")
        }
    }
}
