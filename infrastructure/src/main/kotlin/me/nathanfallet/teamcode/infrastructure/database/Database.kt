package me.nathanfallet.teamcode.infrastructure.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/** Exposed transactions over a bounded HikariCP pool; schema changes belong to Flyway. */
class Database(url: String, username: String, password: String, poolSize: Int = DEFAULT_POOL_SIZE) : AutoCloseable {
    init { require(poolSize in 2..64) { "The database pool size must be between 2 and 64." } }
    private val source = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url; this.username = username; this.password = password
        addDataSourceProperty("connectTimeout", "5000")
        addDataSourceProperty("socketTimeout", "10000")
        addDataSourceProperty("sessionVariables", "innodb_lock_wait_timeout=5")
        maximumPoolSize = poolSize; connectionTimeout = 5_000; transactionIsolation = "TRANSACTION_READ_COMMITTED"
    })
    private val exposed = ExposedDatabase.connect(source, databaseConfig = DatabaseConfig {
        defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
        defaultMaxAttempts = 1
        sqlLogger = object : SqlLogger {
            override fun log(context: StatementContext, transaction: Transaction) = Unit
        }
    })
    suspend fun <T> query(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
        transaction(exposed) { block() }
    }
    fun migrate() { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate() }
    suspend fun ready(): Boolean = withContext(Dispatchers.IO) {
        try { source.connection.use { it.isValid(2) } } catch (_: java.sql.SQLException) { false }
    }
    override fun close() {
        TransactionManager.closeAndUnregister(exposed)
        source.close()
    }
    companion object { const val DEFAULT_POOL_SIZE = 16 }
}
