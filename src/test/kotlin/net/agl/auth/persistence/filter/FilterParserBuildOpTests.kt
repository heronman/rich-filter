package net.agl.auth.persistence.filter

import net.agl.rest.filter.FilterParser
import net.agl.rest.filter.OpNode
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilterParserBuildOpTests : TestDatabase() {

    @Test
    fun `eq operator generates correct SQL`() = transaction {
        val f = OpNode("age", "eq", 30)
        val op = FilterParser.buildOp(f, TestUsersTable)
        val sql = TestUsersTable.selectAll().where { op(this) }.prepareSQL(this, false)
        assertTrue(sql.contains("AGE = 30"))
    }

    @Test
    fun `like operator works`() = transaction {
        val f = OpNode("username", "like", "%john%")
        val op = FilterParser.buildOp(f, TestUsersTable)

        val sql = TestUsersTable.selectAll().where { op(this) }.prepareSQL(this, false)

        assertTrue(sql.contains("USERNAME"))
        assertTrue(sql.contains("LIKE"))
    }

    @Test
    fun `gt operator works with nullsFirst true`() = transaction {
        val f = OpNode("age", "gt", 20, nullsFirst = true)
        val op = FilterParser.buildOp(f, TestUsersTable)

        val sql = TestUsersTable.selectAll().where { op(this) }.prepareSQL(this, false)
        assertTrue(sql.contains("AGE > 20"))
    }

    @Test
    fun `in operator works`() = transaction {
        val f = OpNode("id", "in", listOf(1L, 2L, 3L))
        val op = FilterParser.buildOp(f, TestUsersTable)

        val sql = TestUsersTable.selectAll().where { op(this) }.prepareSQL(this, false)
        assertTrue(sql.contains("ID IN (1, 2, 3)"))
    }
}
