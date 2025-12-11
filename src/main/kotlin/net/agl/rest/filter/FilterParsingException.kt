package net.agl.rest.filter

import com.fasterxml.jackson.core.JsonParseException

class FilterParsingException(val path: String, message: String? = null, cause: Throwable? = null) : IllegalStateException(message, cause) {
    companion object {
        fun wrapJsonParserException(src: String?, e: JsonParseException): FilterParsingException =
            FilterParsingException(e.processor.parsingContext.pathAsPointer().toString(), buildJsonParserErrorMessage(src, e), e)

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
