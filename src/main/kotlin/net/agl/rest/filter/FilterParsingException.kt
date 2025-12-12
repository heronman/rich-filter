package net.agl.rest.filter

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode

open class FilterParsingException(
    message: String? = null,
    cause: Throwable? = null,
    val node: JsonNode? = null,
    val path: List<String> = listOf(),
    val token: String? = null,
) : IllegalStateException(message, cause) {

    constructor(
        message: String,
        node: JsonNode? = null,
        path: List<String>,
        token: String? = null
    ) : this(message, null, node, path, token)

    companion object {
        fun wrapJsonParserException(src: String?, e: JsonParseException): FilterParsingException =
            FilterParsingException(buildJsonParserErrorMessage(src, e), e)

        fun buildJsonParserErrorMessage(src: String?, e: JsonParseException): String {
            val sb = StringBuilder(e.originalMessage)
            sb.append(" at ['").append(e.processor.parsingContext.pathAsPointer()).append("]' - ")
            if (src == null || src.count { it == '\n' } > 0)
                sb.append("in JSON line: ${e.location.lineNr}, column: ${e.location.columnNr}")
            else sb.append("character: ${e.location.charOffset}")
            return sb.toString()
        }

        fun at(path: List<String>) = path.takeIf { it.isNotEmpty() }?.let { " at [/${it.joinToString("/")}]" } ?: ""
    }
}

class InvalidNodeException(node: JsonNode, path: List<String>, message: String? = null) : FilterParsingException(
    "${message ?: "Invalid node"}: $node${at(path)}"
)

class InvalidValueNodeException(node: JsonNode, path: List<String>, message: String? = null) : FilterParsingException(
    "${message ?: "Invalid value node"}: $node${at(path)}"
)

class InvalidOperatorException(op: String, node: JsonNode, path: List<String>) : FilterParsingException(
    "Invalid operator '$op' in node $node${at(path)}",
    node, path, op
)

class OverridingFieldNameException(field: String, node: JsonNode, path: List<String>) : FilterParsingException(
    "Field name is already set ('$field'), can not override it from the given node $node${at(path)}",
    node, path, field
)

class MissingFieldNameException(node: JsonNode, path: List<String>) :
    FilterParsingException("Field name is not set and is not provided in the node $node${at(path)}", node, path)

class OverridingOpNameException(op: String, node: JsonNode, path: List<String>) : FilterParsingException(
    "Operator name ('$op') is already set, can not override it from the given node $node${at(path)}",
    node, path, op
)

class MissingOpNameException(node: JsonNode, path: List<String>) :
    FilterParsingException("Operator name is not set and is not provided in the node $node${at(path)}", node, path)

class NonContainerNodeException(node: JsonNode, path: List<String>) : FilterParsingException(
    "A container node is expected, got: $node${at(path)}",
    node, path
)
