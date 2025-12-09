package net.agl.auth.persistence.filter

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import java.sql.Connection

open class TestDatabase {
    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            Database.connect("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;", driver = "org.h2.Driver")
            TransactionManager.manager.defaultIsolationLevel =
                Connection.TRANSACTION_READ_COMMITTED

            transaction {
                SchemaUtils.create(TestUsersTable)
            }
        }
    }
}
