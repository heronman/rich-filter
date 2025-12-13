package net.agl.rest.filter

sealed interface FilterNode {
    companion object {
        val AGGREGATORS = setOf("and", "or")

        fun and(nodes: List<FilterNode>): FilterNode = logicalBlock(nodes, ::AndNode)
        fun or(nodes: List<FilterNode>): FilterNode = logicalBlock(nodes, ::OrNode)

        private fun logicalBlock(nodes: List<FilterNode>, op: (List<FilterNode>) -> FilterNode): FilterNode =
            when (nodes.size) {
                0 -> error("AND/OR block must not be empty")
                1 -> nodes.first()
                else -> op(nodes)
            }
    }
}

data class AndNode(val nodes: List<FilterNode>) : FilterNode {
    override fun toString(): String = "(${nodes.joinToString(" and ")})"
}

data class OrNode(val nodes: List<FilterNode>) : FilterNode {
    override fun toString(): String = "(${nodes.joinToString(" or ")})"
}
