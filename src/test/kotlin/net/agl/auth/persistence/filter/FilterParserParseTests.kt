package net.agl.auth.persistence.filter

import com.fasterxml.jackson.core.JsonParseException
import net.agl.rest.filter.AndNode
import net.agl.rest.filter.FilterParser
import net.agl.rest.filter.OpNode
import net.agl.rest.filter.OrNode
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FilterParserParseTests {

    @Test
    fun `parse simple eq`() {
        val json = """{ "age": { "eq": 30 } }"""
        val f = FilterParser.parse(json)

        assertTrue(f is OpNode)
        val n = f as OpNode

        assertEquals("age", n.field)
        assertEquals("eq", n.op)
        assertEquals(30, n.value)
    }

    @Test
    fun `parse nested and-or`() {
        val json = """
            {
              "and": [
                { "age": { "gt": 20 }},
                { "or": [
                    { "name": { "like": "a%" }},
                    { "name": { "eq": "john" }}
                ]}
              ]
            }
        """.trimIndent()

        val f = FilterParser.parse(json)
        assertTrue(f is AndNode)
        val root = f as AndNode

        assertEquals(2, root.nodes.size)
        assertTrue(root.nodes[1] is OrNode)
    }

    @Test
    fun `parse 'or' nested in named node`() {
        val json = """
            {
              "name": { "or": [
                { "like": "a%" },
                { "eq": "john" }
              ]}
            }
        """.trimIndent()

        val f = FilterParser.parse(json)
        assertTrue(f is OrNode)
        (f as OrNode).let { root ->
            assertEquals(2, root.nodes.size)
            assertTrue(root.nodes[0] is OpNode)
            (root.nodes[0] as OpNode).let { op ->
                assertEquals("like", op.op)
                assertEquals("a%", op.value)
            }
            assertTrue(root.nodes[1] is OpNode)
            (root.nodes[1] as OpNode).let { op ->
                assertEquals("eq", op.op)
                assertEquals("john", op.value)
            }
        }

    }

    @Test
    fun `parse IN array`() {
        val json = """{ "id": [1,2,3] }"""
        val f = FilterParser.parse(json)
        val n = f as OpNode

        assertEquals("in", n.op)
        assertEquals(listOf(1,2,3), n.value)
    }

    @Test
    fun `duplicate keys cause json parse error`() {
        val json = """{ "age": { "eq": 30, "eq": 31 } }"""

        val ex = assertThrows(JsonParseException::class.java) {
            FilterParser.parse(json)
        }
        assertTrue(ex.originalMessage!!.contains("Duplicate field 'eq'"))
    }
}
