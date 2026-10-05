package com.yonagi.verse.support;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

class MySqlTestDatabaseTest {
    @Test void rejectsMissingConfigurationAndExistingBusinessDatabaseUrls() {
        for (String url : new String[]{null, "", "jdbc:h2:mem:test", "jdbc:mysql://localhost:3306/verse",
                "jdbc:mysql://localhost:3306/verse?useSSL=false"}) {
            assertThrows(IllegalArgumentException.class, () -> MySqlTestDatabase.validateServerUrl(url));
        }
    }

    @Test void isolatesConcurrentFixturesAndDropsOnlyItsOwnDatabase() throws Exception {
        try (var first = MySqlTestDatabase.create(); var second = MySqlTestDatabase.create()) {
            try (var connection = first.getConnection()) {
                assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            }
            var firstJdbc = new JdbcTemplate(first);
            var secondJdbc = new JdbcTemplate(second);
            String firstName = firstJdbc.queryForObject("SELECT DATABASE()", String.class);
            String secondName = secondJdbc.queryForObject("SELECT DATABASE()", String.class);
            assertNotEquals(firstName, secondName);
            firstJdbc.execute("CREATE TABLE fixture(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
            secondJdbc.execute("CREATE TABLE fixture(id BIGINT PRIMARY KEY) ENGINE=InnoDB");
            firstJdbc.update("INSERT INTO fixture VALUES(1)");
            assertEquals(0, secondJdbc.queryForObject("SELECT COUNT(*) FROM fixture", Integer.class));
            first.close();
            assertEquals(0, secondJdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, firstName));
            secondJdbc.update("INSERT INTO fixture VALUES(2)");
            assertEquals(1, secondJdbc.queryForObject("SELECT COUNT(*) FROM fixture", Integer.class));
        }
    }
}
