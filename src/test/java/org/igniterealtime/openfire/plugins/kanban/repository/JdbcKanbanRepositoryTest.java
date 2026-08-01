package org.igniterealtime.openfire.plugins.kanban.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;

import org.igniterealtime.openfire.plugins.kanban.TestDatabase;
import org.igniterealtime.openfire.plugins.kanban.model.Board;
import org.junit.jupiter.api.Test;

class JdbcKanbanRepositoryTest {
    @Test
    void rollsBackRuntimeFailures() throws Exception {
        final JdbcKanbanRepository repository = TestDatabase.repository();

        assertThrows(IllegalArgumentException.class, () -> repository.transact(transaction -> {
            transaction.insertBoard(new Board("board", "Board", 1, "owner@example.org", 1, 1));
            throw new IllegalArgumentException("abort");
        }));

        assertEquals(0, repository.diagnostics(1).boards());
    }

    @Test
    void wrapsCheckedSqlFailuresAfterRollback() throws Exception {
        final JdbcKanbanRepository repository = TestDatabase.repository();

        assertThrows(IllegalStateException.class, () -> repository.transact(transaction -> {
            throw new SQLException("database failure");
        }));
    }
}
