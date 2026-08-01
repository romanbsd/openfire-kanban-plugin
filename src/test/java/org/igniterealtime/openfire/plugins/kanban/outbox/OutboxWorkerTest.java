package org.igniterealtime.openfire.plugins.kanban.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.igniterealtime.openfire.plugins.kanban.TestDatabase;
import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository;
import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository.OutboxEntry;
import org.igniterealtime.openfire.plugins.kanban.service.KanbanService;
import org.junit.jupiter.api.Test;

class OutboxWorkerTest {
    @Test
    void drainsCreatedBoardInSequenceAndMarksDelivery() throws Exception {
        final JdbcKanbanRepository repository = TestDatabase.repository();
        new KanbanService(repository, 0).createBoard("owner@example.org", "Board", 0);
        final List<OutboxEntry> delivered = new ArrayList<>();

        new OutboxWorker(repository, delivered::add).run();

        assertEquals(3, delivered.size());
        assertTrue(delivered.get(0).sequence() < delivered.get(1).sequence());
        assertEquals(0, repository.diagnostics(System.currentTimeMillis()).pending());
    }

    @Test
    void failureReturnsItemToPendingWithBackoff() throws Exception {
        final JdbcKanbanRepository repository = TestDatabase.repository();
        final Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC);
        new KanbanService(repository, clock, () -> UUID.randomUUID().toString(), () -> 0, jid -> true)
            .createBoard("owner@example.org", "Board", 0);
        final AtomicBoolean failed = new AtomicBoolean();
        final OutboxWorker worker = new OutboxWorker(repository, entry -> {
            failed.set(true);
            throw new IllegalStateException("offline");
        }, clock, "worker");

        worker.run();

        assertTrue(failed.get());
        assertEquals(3, repository.diagnostics(10_000).pending());
        assertEquals(1, repository.diagnostics(10_001).retrying());
    }

    @Test
    void reclaimsExpiredLeasesWithoutBreakingPerBoardOrder() throws Exception {
        final JdbcKanbanRepository repository = TestDatabase.repository();
        final Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC);
        new KanbanService(repository, clock, () -> UUID.randomUUID().toString(), () -> 0, jid -> true)
            .createBoard("owner@example.org", "Board", 0);

        final OutboxEntry first = repository.claimNext("worker-a", 10_000, 11_000).orElseThrow();
        assertTrue(repository.claimNext("worker-b", 10_500, 12_000).isEmpty());
        final OutboxEntry reclaimed = repository.claimNext("worker-b", 11_001, 12_000).orElseThrow();
        assertEquals(first.id(), reclaimed.id());
        repository.markDelivered(reclaimed.id(), "worker-a", 11_002);
        assertEquals(1, repository.diagnostics(11_002).publishing());
        repository.markDelivered(reclaimed.id(), "worker-b", 11_002);
        assertEquals(0, repository.diagnostics(11_002).publishing());
        assertFalse(repository.claimNext("worker-c", 11_002, 12_000).isEmpty());
    }

    @Test
    void purgesDeliveredHistoryWhileKeepingBoardSequenceMonotonic() throws Exception {
        final JdbcKanbanRepository repository = TestDatabase.repository();
        final Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC);
        final KanbanService service = new KanbanService(
            repository, clock, () -> UUID.randomUUID().toString(), () -> 0, jid -> true);
        final var board = service.createBoard("owner@example.org", "Board", 0);
        new OutboxWorker(repository, entry -> {}, clock, "worker").run();

        final var purged = repository.purgeHistory(15_000);
        assertEquals(3, purged.outboxRows());
        assertEquals(1, purged.activityRows());

        service.createColumn("owner@example.org", board.value().id(), "Todo", null, 1);
        final List<OutboxEntry> delivered = new ArrayList<>();
        new OutboxWorker(repository, delivered::add, clock, "worker-2").run();
        assertEquals(1, delivered.size());
        assertTrue(delivered.get(0).sequence() > 10);
    }
}
