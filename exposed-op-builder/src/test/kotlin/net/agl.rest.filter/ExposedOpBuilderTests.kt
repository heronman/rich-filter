package net.agl.rest.filter

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.*

class ExposedOpBuilderTests : TestDatabase() {

    @Test
    fun `eq operator generates correct SQL`() = transaction {
        val f = OpNode("age", "eq", 30)
        val op = ExposedOpBuilder.buildOp(f, TestUsersTable)
        val sql = TestUsersTable.selectAll().where { op(this) }.prepareSQL(this, false)
        assertTrue(sql.contains("AGE = 30"))
    }

    @Test
    fun `like operator works`() = transaction {
        val f = OpNode("username", "like", "%john%")
        val op = ExposedOpBuilder.buildOp(f, TestUsersTable)

        val sql = TestUsersTable.selectAll().where { op(this) }.prepareSQL(this, false)

        assertTrue(sql.contains("USERNAME"))
        assertTrue(sql.contains("LIKE"))
    }

    @Test
    fun `gt operator works with nullsFirst true`() = transaction {
        val f = OpNode("age", "gt", 20, nullsOrder = NullsOrder.NULLS_FIRST)
        val op = ExposedOpBuilder.buildOp(f, TestUsersTable)

        val sql = TestUsersTable.selectAll().where { op(this) }.prepareSQL(this, false)
        assertTrue(sql.contains("AGE > 20"))
    }

    @Test
    fun `in operator works`() = transaction {
        val uid1 = UUID.randomUUID()
        val uid2 = UUID.randomUUID()
        val uid3 = UUID.randomUUID()
        val f = OpNode("id", "in", listOf(uid1, uid2, uid3))
        val op = ExposedOpBuilder.buildOp(f, TestUsersTable)

        val sql = TestUsersTable.selectAll().where { op(this) }.prepareSQL(this, false)
        assertTrue(sql.contains("ID IN ('$uid1', '$uid2', '$uid3')"))
    }
}