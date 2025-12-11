package net.agl.rest.filter

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.JavaInstantColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.sql.javatime.JavaLocalDateTimeColumnType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.reflect.full.isSubclassOf

private typealias ConditionOp = (ExpressionWithColumnType<*>, Any?, Boolean?) -> SqlExpressionBuilder.() -> Op<Boolean>

class ConditionBuilder {
    companion object {

        fun buildOp(field: OpNode, table: Table): SqlExpressionBuilder.() -> Op<Boolean> {
            val column = table.columns.find { it.name == field.field }
                ?: error("Field '${field.field}' not found in the table '${table.tableName}'")

            @Suppress("UNCHECKED_CAST")
            val preparedColumn = if (column.columnType is StringColumnType)
                (column as ExpressionWithColumnType<String>).lowerCase() else column
            val preparedValue =
                if (column.columnType is StringColumnType && field.value is String && !field.caseSensitive.asBoolean)
                    field.value.lowercase() else field.value

            return OPS[field.op]?.let {
                it(preparedColumn, preparedValue, field.nullsOrder.asBoolean)
            } ?: error("Invalid operator: ${field.op}")
        }

        fun buildOp(filter: FilterNode, table: Table): SqlExpressionBuilder.() -> Op<Boolean> =
            {
                when (filter) {
                    is AndNode -> filter.nodes.map { buildOp(it, table).invoke(this) }
                        .compoundAnd()

                    is OrNode -> filter.nodes.map { buildOp(it, table).invoke(this) }
                        .compoundOr()

                    is net.agl.rest.filter.OpNode -> buildOp(filter, table).invoke(this)
                }
            }

        //

        @Suppress("UNCHECKED_CAST")
        private fun <T> castValue(type: IColumnType<T>, value: Any?): T? = value?.let {
            if (type is StringColumnType && it is String) (it as T)
            else type.valueFromDB(it)
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

            return { op(this, exp as ExpressionWithColumnType<Comparable<Any>>, converted as Comparable<Any>) }
        }

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

    }
}