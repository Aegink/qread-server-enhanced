package web.config

import com.zaxxer.hikari.HikariConfig
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseConfigurationTest {
    @Test
    fun sqliteConfigurationUsesStandardPoolProperties() {
        listOf(
            Path.of("conf", "conf.yml") to "jdbc:sqlite:",
            Path.of("src", "main", "resources", "conf.yml") to "jdbc:p6spy:sqlite:"
        ).forEach { (path, jdbcPrefix) ->
            val config = readDataSource(path)

            assertTrue(config.getValue("jdbcUrl").toString().startsWith(jdbcPrefix))
            assertFalse(config.containsKey("maxPoolSize"))
            assertEquals(4, config.getValue("maximumPoolSize"))
            assertEquals(1, config.getValue("minimumIdle"))
            assertEquals(30_000, config.getValue("connectionTimeout"))
            assertEquals(5_000, config.getValue("validationTimeout"))
            assertHikariCanBind(config)
        }
    }

    @Test
    fun mysqlConfigurationUsesProductionPoolPropertiesAndPlaceholders() {
        listOf(
            Path.of("conf", "mysqlconf.yml"),
            Path.of("src", "main", "resources", "confmysql.yml")
        ).forEach { path ->
            val config = readDataSource(path)

            assertEquals("用户", config.getValue("username"))
            assertEquals("密码", config.getValue("password"))
            assertFalse(config.getValue("jdbcUrl").toString().contains("192.168.20.2"))
            assertEquals(20, config.getValue("maximumPoolSize"))
            assertEquals(5, config.getValue("minimumIdle"))
            assertEquals(30_000, config.getValue("connectionTimeout"))
            assertEquals(5_000, config.getValue("validationTimeout"))
            assertEquals(600_000, config.getValue("idleTimeout"))
            assertEquals(1_800_000, config.getValue("maxLifetime"))
            assertHikariCanBind(config)
        }
    }

    @Test
    fun sqliteUrlAppliesWalAndBusyTimeout() {
        val configuredUrl = readDataSource(Path.of("conf", "conf.yml")).getValue("jdbcUrl").toString()
        val parameters = configuredUrl.substringAfter('?', "")
        val database = Files.createTempFile("qread-stage16-", ".db")
        val jdbcUrl = "jdbc:sqlite:${database.toAbsolutePath().toString().replace('\\', '/')}?$parameters"

        try {
            DriverManager.getConnection(jdbcUrl).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA journal_mode").use { result ->
                        assertTrue(result.next())
                        assertEquals("wal", result.getString(1).lowercase())
                    }
                    statement.executeQuery("PRAGMA busy_timeout").use { result ->
                        assertTrue(result.next())
                        assertEquals(5_000, result.getInt(1))
                    }
                    statement.executeQuery("PRAGMA synchronous").use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                }
            }
        } finally {
            database.deleteIfExists()
            Path.of("$database-shm").deleteIfExists()
            Path.of("$database-wal").deleteIfExists()
        }
    }

    private fun assertHikariCanBind(config: Map<String, Any>) {
        val properties = Properties()
        listOf(
            "maximumPoolSize",
            "minimumIdle",
            "connectionTimeout",
            "validationTimeout",
            "idleTimeout",
            "maxLifetime"
        ).forEach { key ->
            config[key]?.let { properties.setProperty(key, it.toString()) }
        }

        HikariConfig(properties).also { hikari ->
            assertEquals(config.getValue("maximumPoolSize"), hikari.maximumPoolSize)
            assertEquals(config.getValue("minimumIdle"), hikari.minimumIdle)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readDataSource(path: Path): Map<String, Any> {
        Files.newBufferedReader(path).use { reader ->
            val yaml = Yaml().load<Map<String, Any>>(reader)
            val dataSources = yaml.getValue("solon.dataSources") as Map<String, Any>
            return dataSources.getValue("db") as Map<String, Any>
        }
    }
}
