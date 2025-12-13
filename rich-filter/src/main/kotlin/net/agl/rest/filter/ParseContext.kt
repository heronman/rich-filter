package net.agl.rest.filter

sealed interface ParseContext {
    data object Root : ParseContext

    data class Field(val name: String) : ParseContext

    data class Op(val field: String, val op: String) : ParseContext

    data class Agg(val kind: String, val field: String?) : ParseContext

}