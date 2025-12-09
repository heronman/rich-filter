package net.agl.rest.filter

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jetbrains.exposed.sql.BooleanColumnType
import org.jetbrains.exposed.sql.DecimalColumnType
import org.jetbrains.exposed.sql.DoubleColumnType
import org.jetbrains.exposed.sql.ExpressionWithColumnType
import org.jetbrains.exposed.sql.FloatColumnType
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ShortColumnType
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.StringColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.compoundAnd
import org.jetbrains.exposed.sql.compoundOr
import org.jetbrains.exposed.sql.javatime.JavaInstantColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalDateTimeColumnType
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.reflect.full.isSubclassOf

private typealias ConditionOp = (ExpressionWithColumnType<*>, Any?, Boolean?) -> SqlExpressionBuilder.() -> Op<Boolean>

sealed interface FilterNode
data class AndNode(val nodes: List<FilterNode>) : FilterNode {
    override fun toString(): String = "(${nodes.joinToString(" and ")})"
}

data class OrNode(val nodes: List<FilterNode>) : FilterNode {
    override fun toString(): String = "(${nodes.joinToString(" or ")})"
}

data class OpNode(
    val field: String,
    val op: String,
    val value: Any?,
    val caseSensitive: Boolean = true,
    val nullsFirst: Boolean? = null,
) : FilterNode {
    override fun toString(): String {
        val nullsOrder = when {
            op !in setOf("gt", "ge", "lt", "le") -> null
            nullsFirst == true -> "NF"
            nullsFirst == false -> "NL"
            else -> null
        }

        val sb = StringBuilder().append('`').append(field).append("` ").append(op)
        if (!caseSensitive || nullsOrder != null) {
            sb.append("(")
            if (!caseSensitive) {
                sb.append("CI")
                nullsOrder?.let { sb.append(",") }
            }
            nullsOrder?.let { sb.append(it) }
            sb.append(")")
        }
        if (value is Collection<*>) sb.append(value.map { "'$it'" })
        else sb.append(" '").append(value).append('\'')
        return sb.toString()
    }
}

private data class Operation(val value: Any?, val caseSensitive: Boolean?, val nullsFirst: Boolean?)

object FilterParser {
    private val mapper = ObjectMapper().configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true)

    fun parse(src: String): FilterNode {
        val json = mapper.readTree(src)
        return parse(json, emptyList(), null, ::AndNode, true, true)
    }

    fun buildOp(field: OpNode, table: Table): SqlExpressionBuilder.() -> Op<Boolean> {
        val column = table.columns.find { it.name == field.field }
            ?: error("Field '${field.field}' not found in the table '${table.tableName}'")

        @Suppress("UNCHECKED_CAST")
        val preparedColumn = if (column.columnType is StringColumnType)
            (column as ExpressionWithColumnType<String>).lowerCase() else column
        val preparedValue = if (column.columnType is StringColumnType && field.value is String && !field.caseSensitive)
            field.value.lowercase() else field.value

        return OPS[field.op]?.let {
            it(preparedColumn, preparedValue, field.nullsFirst)
        } ?: error("Invalid operator: ${field.op}")
    }

    fun buildOp(filter: FilterNode, table: Table): SqlExpressionBuilder.() -> Op<Boolean> = {
        when (filter) {
            is AndNode -> filter.nodes.map { buildOp(it, table).invoke(this) }.compoundAnd()
            is OrNode -> filter.nodes.map { buildOp(it, table).invoke(this) }.compoundOr()
            is OpNode -> buildOp(filter, table).invoke(this)
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

    private fun parseList(
        node: JsonNode,
        path: List<String>,
        field: String?,
        defaultCaseSensitive: Boolean,
        defaultNullsFirst: Boolean?
    ): List<FilterNode> = when {
        node.isEmpty -> error("Invalid list node: must be non-empty array or object")
        node.isArray -> node.mapIndexed { key, item ->
            parse(
                item,
                path + key.toString(),
                field,
                ::AndNode,
                defaultCaseSensitive,
                defaultNullsFirst
            )
        }

        node.isObject -> node.properties().map { (key, item) ->
            parse(
                item,
                path + key,
                field,
                ::AndNode,
                defaultCaseSensitive,
                defaultNullsFirst
            )
        }

        else -> error("This line must not be reached. Check the code")
    }

    private fun parse(
        node: JsonNode,
        path: List<String>,
        field: String?,
        aggregator: (List<FilterNode>) -> FilterNode,
        defaultCaseSensitive: Boolean,
        defaultNullsFirst: Boolean?
    ): FilterNode =
        if (node.isArray) {
            if (field == null)
                parseList(node, path, null, defaultCaseSensitive, defaultNullsFirst)
                    .let { if (it.size == 1) it[0] else AndNode(it) }
            else parseField(field, "in", node, true, true)
        } else if (node.isObject) {
            val caseSensitive = node["CS"]?.asBoolean() ?: defaultCaseSensitive
            val nullsFirst = if (node.has("NF")) {
                node["NF"].takeIf { !it.isNull }?.asBoolean()
            } else defaultCaseSensitive

            val filters = node.fieldNames().asSequence().mapNotNull { element ->
                when (element) {
                    "and" -> AndNode(parseList(node[element], path + element, field, caseSensitive, nullsFirst))
                    "or" -> OrNode(parseList(node[element], path + element, field, caseSensitive, nullsFirst))
                    "CI", "nullsFirst" -> null
                    in OPS.keys -> {
                        if (field == null)
                            error("No field name set - no comparison operations available (${path.joinToString(".")})")
                        val nullsFirst = node["NF"]?.asBoolean() ?: defaultNullsFirst
                        parseField(field, element, node[element], caseSensitive, nullsFirst)
                    }

                    else -> {
                        if (field != null)
                            error(
                                "Invalid element name: \"$element\" while field name already set ($field) (${
                                    path.joinToString(
                                        "."
                                    )
                                })"
                            )
                        parse(node[element], path + element, element, ::AndNode, caseSensitive, nullsFirst)
                    }
                }
            }.toList()
            if (filters.size == 1) filters[0] else aggregator(filters)
        } else {
            if (field == null)
                error("No field name set - no comparison operations available (${path.joinToString(".")})")
            parseField(field, "eq", node, true, true)
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> castValue(type: IColumnType<T>, value: Any?): T? = value?.let {
        if (type is StringColumnType && it is String) (it as T)
        else type.valueFromDB(it)
    }

    private fun parseField(
        field: String,
        op: String,
        valueNode: JsonNode,
        caseSensitive: Boolean,
        nullsFirst: Boolean?
    ) = extractNode(valueNode).let {
        if (it is Operation)
            OpNode(field, op, it.value, it.caseSensitive ?: caseSensitive, it.nullsFirst ?: nullsFirst)
        else OpNode(field, op, it, caseSensitive, nullsFirst)
    }

    private fun extractNode(n: JsonNode): Any? = when {
        n.isArray -> n.map(::extractNode)
        n.isObject -> {
            if (n.fieldNames().asSequence().toSet().let {
                    "value" in it && (it - setOf("value", "CS", "NF")).isEmpty()
                }) Operation(
                extractNode(n["value"]),
                n["CS"]?.takeIf { it.isBoolean }?.asBoolean(),
                n["NF"]?.takeIf { it.isBoolean }?.asBoolean()
            ) else n.properties().associate { (k, v) -> k to extractNode(v) }
        }

        n.isNull || n.isMissingNode -> null
        n.isBoolean -> n.asBoolean()
        n.isDouble -> n.asDouble()
        n.isFloat -> n.asDouble().toFloat()
        n.isLong -> n.asLong()
        n.isInt -> n.asInt()
        n.isShort -> n.asInt().toShort()
        n.isTextual -> n.asText()
        else -> n.asText()
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun collectionCondition(
        exp: ExpressionWithColumnType<*>,
        value: Iterable<Any>,
        crossinline op: SqlExpressionBuilder.(ExpressionWithColumnType<Comparable<Any>>, Collection<Comparable<Any>>) -> Op<Boolean>
    ): SqlExpressionBuilder.() -> Op<Boolean> {
        val type = exp.columnType
        val converted = value.map { castValue(type, it) }
        return { op(this, exp as ExpressionWithColumnType<Comparable<Any>>, converted as Collection<Comparable<Any>>) }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun condition(
        exp: ExpressionWithColumnType<*>,
        value: Any,
        crossinline op: SqlExpressionBuilder.(ExpressionWithColumnType<Comparable<Any>>, Comparable<Any>) -> Op<Boolean>
    ): SqlExpressionBuilder.() -> Op<Boolean> {

        val type = exp.columnType
        val converted = if (value is Collection<*>) value.map { castValue(type, it) }
        else castValue(type, value)

//        val typedExp: ExpressionWithColumnType<Comparable<Any>> =
//            when (type) {
//                is BooleanColumnType -> exp as ExpressionWithColumnType<Boolean>
//                is IntegerColumnType -> exp as ExpressionWithColumnType<Int>
//                is LongColumnType -> exp as ExpressionWithColumnType<Long>
//                is DecimalColumnType -> exp as ExpressionWithColumnType<BigDecimal>
//                is DoubleColumnType -> exp as ExpressionWithColumnType<Double>
//                is FloatColumnType -> exp as ExpressionWithColumnType<Float>
//                is ShortColumnType -> exp as ExpressionWithColumnType<Short>
//                is StringColumnType -> exp as ExpressionWithColumnType<String>
//                is JavaLocalDateColumnType -> exp as ExpressionWithColumnType<LocalDate>
//                is JavaLocalDateTimeColumnType -> exp as ExpressionWithColumnType<LocalDateTime>
//                is JavaInstantColumnType -> exp as ExpressionWithColumnType<Instant>
//                else -> error("Type is not Comparable: ${type::class.simpleName}")
//            } as ExpressionWithColumnType<Comparable<Any>>
//
//        val typedValue = converted as Comparable<Any>

        return { op(this, exp as ExpressionWithColumnType<Comparable<Any>>, converted as Comparable<Any>) }
    }

    private val eq: ConditionOp =
        { exp, value, _ ->
            value?.let { condition(exp, it) { e, v -> e eq v } } ?: { exp.isNull() }
        }

    private val ne: ConditionOp =
        { exp, value, _ ->
            value?.let { condition(exp, it) { e, v -> e neq v } } ?: { exp.isNotNull() }
        }

    @Suppress("UNCHECKED_CAST")
    private val like: ConditionOp =
        { exp, value, _ -> { (exp as ExpressionWithColumnType<String>) like (value as String) } }

    private val lt: ConditionOp = { exp, value, nullsFirst ->
        areComparable(exp, value)
        value?.let {
            when (nullsFirst) {
                true -> condition(exp, value) { e, v -> e.isNull() or (e less v) }
                else -> condition(exp, value) { e, v -> e less v }
            }
        } ?: {
            when (nullsFirst) {
                false -> exp.isNotNull()
                else -> Op.FALSE
            }
        }
    }

    private val le: ConditionOp = { exp, value, nullsFirst ->
        areComparable(exp, value)
        value?.let {
            when (nullsFirst) {
                true -> condition(exp, value) { e, v -> e.isNull() or (e lessEq v) }
                else -> condition(exp, value) { e, v -> e lessEq v }
            }
        } ?: {
            when (nullsFirst) {
                false -> Op.TRUE
                else -> exp.isNull()
            }
        }
    }

    private val gt: ConditionOp = { exp, value, nullsFirst ->
        areComparable(exp, value)
        value?.let {
            when (nullsFirst) {
                false -> condition(exp, value) { e, v -> e.isNull() or (e greater v) }
                else -> condition(exp, value) { e, v -> e greater v }
            }
        } ?: {
            when (nullsFirst) {
                true -> exp.isNotNull()
                else -> Op.FALSE
            }
        }
    }

    private val ge: ConditionOp = { exp, value, nullsFirst ->
        areComparable(exp, value)
        value?.let {
            when (nullsFirst) {
                false -> condition(exp, value) { e, v -> e.isNull() or (e greaterEq v) }
                else -> condition(exp, value) { e, v -> e greaterEq v }
            }
        } ?: {
            when (nullsFirst) {
                true -> Op.TRUE
                else -> exp.isNull()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private val inList: ConditionOp = { exp, value, _ ->
        collectionCondition(
            exp,
            value!! as Iterable<Any>
        ) { e, v -> e inList (v as Iterable<Comparable<Any>>) }
    }

    @Suppress("UNCHECKED_CAST")
    private val notInList: ConditionOp = { exp, value, _ ->
        collectionCondition(
            exp,
            value!! as Iterable<Any>
        ) { e, v -> e.isNull() or (e notInList (v as Iterable<Comparable<Any>>)) }
    }

    private val OPS = mapOf(
        "eq" to eq,
        "ne" to ne,
        "gt" to gt,
        "ge" to ge,
        "lt" to lt,
        "le" to le,
        "in" to inList,
        "nin" to notInList,
        "like" to like
    )

    private fun areComparable(expr: ExpressionWithColumnType<*>, value: Any?) =
        require(value?.let { value ->
            val valueClass = value::class
            val columnClass = when (expr.columnType) {
                is BooleanColumnType -> Boolean::class
                is IntegerColumnType -> Int::class
                is LongColumnType -> Long::class
                is DecimalColumnType -> BigDecimal::class
                is DoubleColumnType -> Double::class
                is FloatColumnType -> Float::class
                is ShortColumnType -> Short::class
                is StringColumnType -> String::class
                is JavaLocalDateColumnType -> LocalDate::class
                is JavaLocalDateTimeColumnType -> LocalDateTime::class
                is JavaInstantColumnType -> Instant::class
                else -> error("Type is not supported: ${expr.columnType::class.simpleName}")
            }
            valueClass == columnClass ||
                    (valueClass.isSubclassOf(Comparable::class) &&
                            columnClass.isSubclassOf(Comparable::class) &&
                            (valueClass.isSubclassOf(columnClass) || columnClass.isSubclassOf(valueClass)))
        } ?: true) { "Given value is not comparable with ${expr.columnType::class.simpleName}" }

}