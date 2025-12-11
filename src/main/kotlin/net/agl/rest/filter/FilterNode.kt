package net.agl.rest.filter

sealed interface FilterNode {
    companion object {
        val AGGREGATORS = setOf("and", "or")
    }
}

data class AndNode(val nodes: List<FilterNode>) : FilterNode {
    override fun toString(): String = "(${nodes.joinToString(" and ")})"
}

data class OrNode(val nodes: List<FilterNode>) : FilterNode {
    override fun toString(): String = "(${nodes.joinToString(" or ")})"
}
