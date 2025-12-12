package net.agl.rest.filter

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode

class FilterParsingException(
    message: String? = null,
    cause: Throwable? = null,
    val path: List<String> = listOf(),
    val node: JsonNode? = null
) : IllegalStateException(addMessageDetails(message, path, node), cause) {

    constructor(message: String, path: List<String>, node: JsonNode) : this(message, null, path, node)

    companion object {
        val MSG_FIELD_NAME_ALREADY_SET = "Field name is already set, can not override it from the given node %s"
        val MSG_MISSING_FIELD_NAME = "Field name is not set and is not provided in the node %s"
        val MSG_OP_NAME_ALREADY_SET = "Operator name is already set, can not override it from the given node %s"
        val MSG_MISSING_OP_NAME = "Operator name is not set and is not provided in the node %s"
        val MSG_INVALID_NODE = "Invalid value node: %s"
        val MSG_INVALID_OPERATOR = "Invalid operator: %s"
        val MSG_INVALID_VALUE_NODE = "Invalid value node: %s"
        val MSG_CONTAINER_NODE_EXPECTED = "A container node is expected, got: %s"


        fun wrapJsonParserException(src: String?, e: JsonParseException): FilterParsingException =
            FilterParsingException(buildJsonParserErrorMessage(src, e), e)

        fun buildJsonParserErrorMessage(src: String?, e: JsonParseException): String {
            val sb = StringBuilder(e.originalMessage)
            sb.append(" in JSON at ['").append(e.processor.parsingContext.pathAsPointer()).append("' - ")
            if (src == null || src.count { it == '\n' } > 0) sb.append("line: ${e.location.lineNr}, column: ${e.location.columnNr}")
            else sb.append("character: ${e.location.charOffset}")
            sb.append("]")
            return sb.toString()
        }

        private fun addMessageDetails(message: String?, path: List<String>, node: JsonNode?): String =
            "$message${
                path
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("/")
                    ?.let { " at '/$it' ${node?.let { "(${it.nodeType.name})" } ?: ""}" }
                    ?: ""
            }".let {
                if (it.contains("%s")) it.format(node?.toString() ?: "(unknown)")
                else it
            }
    }
}
