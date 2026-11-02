package ir.darsyar.backend

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

object Database {
    fun connectAndMigrate(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = env("DB_URL", "jdbc:postgresql://localhost:5433/darsyar_staging")
            username = env("DB_USER", "darsyar_dev")
            password = env("DB_PASSWORD", "darsyar_dev_only")
            maximumPoolSize = env("DB_POOL_SIZE", "5").toInt()
            connectionTimeout = 5_000
            validationTimeout = 3_000
            initializationFailTimeout = 10_000
            poolName = "darsyar-backend-pool"
        }
        val dataSource = HikariDataSource(config)
        try {
            Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true).load().migrate()
        } catch (error: Throwable) {
            dataSource.close()
            throw error
        }
        return dataSource
    }
    private fun env(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
}
