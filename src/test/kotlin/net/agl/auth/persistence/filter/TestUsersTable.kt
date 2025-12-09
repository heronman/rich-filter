package net.agl.auth.persistence.filter

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object TestUsersTable : Table("users") {
    val id = long("id")
    val username = varchar("username", 128)
    val age = integer("age")
    val balance = decimal("balance", 10, 2)
    val createdAt = timestamp("created_at")
}

