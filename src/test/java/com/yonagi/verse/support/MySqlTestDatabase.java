package com.yonagi.verse.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.regex.Pattern;

/** 每个用例只创建和清理自己的随机 MySQL 库，不连接现有业务库。 */
public final class MySqlTestDatabase extends AbstractDataSource implements AutoCloseable {
    private static final Pattern SERVER_URL = Pattern.compile("^jdbc:mysql://[^/?#]+/?(\\?[^#]*)?$");
    private final DriverManagerDataSource server;
    private final HikariDataSource database;
    private final String databaseName;
    private boolean closed;

    private MySqlTestDatabase(String url, String user, String password) {
        validateServerUrl(url);
        server = new DriverManagerDataSource(url, user, password);
        databaseName = "verse_test_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(server).execute("CREATE DATABASE `" + databaseName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        HikariDataSource pool = null;
        try {
            int queryStart = url.indexOf('?');
            String address = queryStart < 0 ? url : url.substring(0, queryStart);
            String query = queryStart < 0 ? "" : url.substring(queryStart);
            if (address.endsWith("/")) address = address.substring(0, address.length() - 1);
            var config = new HikariConfig();
            config.setJdbcUrl(address + "/" + databaseName + query);
            config.setUsername(user);
            config.setPassword(password);
            config.setMaximumPoolSize(16);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(10_000);
            config.setPoolName(databaseName);
            pool = new HikariDataSource(config);
            try (var connection = pool.getConnection()) {
                if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())) {
                    throw new IllegalStateException("Database tests require real MySQL");
                }
            }
            database = pool;
        } catch (SQLException | RuntimeException failure) {
            if (pool != null) pool.close();
            try { dropDatabase(); }
            catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("Cannot initialize isolated MySQL test database", failure);
        }
    }

    public static MySqlTestDatabase create() {
        return new MySqlTestDatabase(setting("URL"), setting("USER"), setting("PASSWORD"));
    }

    private static String setting(String suffix) {
        String value = System.getenv("VERSE_TEST_MYSQL_" + suffix);
        // 保留既有预算测试运行脚本的环境变量兼容性。
        return value != null ? value : System.getenv("VERSE_BUDGET_TEST_" + suffix);
    }

    static void validateServerUrl(String url) {
        if (url == null || !SERVER_URL.matcher(url).matches()) {
            throw new IllegalArgumentException("Set VERSE_TEST_MYSQL_URL to a MySQL server URL without a database name, "
                    + "and VERSE_TEST_MYSQL_USER/PASSWORD to a test account with CREATE/DROP DATABASE privileges");
        }
    }

    @Override public Connection getConnection() throws SQLException { return database.getConnection(); }
    @Override public Connection getConnection(String username, String password) throws SQLException {
        return database.getConnection(username, password);
    }

    @Override public void close() {
        if (!closed) {
            database.close();
            dropDatabase();
            closed = true;
        }
    }

    private void dropDatabase() {
        if (!databaseName.matches("verse_test_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe test database name");
        new JdbcTemplate(server).execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
    }
}
