package org.igniterealtime.openfire.plugins.kanban;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import com.google.common.base.Splitter;
import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository;

public final class TestDatabase {
    private TestDatabase() {}

    public static JdbcKanbanRepository repository() throws Exception {
        final String url = "jdbc:hsqldb:mem:kanban-" + UUID.randomUUID() + ";shutdown=false";
        try (Connection connection = DriverManager.getConnection(url, "SA", ""); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ofVersion (name VARCHAR(50) PRIMARY KEY, version INT NOT NULL)");
            final String script = Files.readString(Path.of("src/main/database/kanban_hsqldb.sql"));
            for (String sql : Splitter.on(';').split(script)) {
                if (!sql.isBlank()) {
                    statement.execute(sql.trim());
                }
            }
        }
        return new JdbcKanbanRepository(() -> DriverManager.getConnection(url, "SA", ""));
    }
}
