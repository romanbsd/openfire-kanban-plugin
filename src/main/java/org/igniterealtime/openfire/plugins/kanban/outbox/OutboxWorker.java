package org.igniterealtime.openfire.plugins.kanban.outbox;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository;
import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository.OutboxEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Claims and delivers a bounded batch while preserving per-board sequence. */
public final class OutboxWorker implements Runnable {
    private static final Logger Log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final long LEASE_MILLIS = 30_000;
    private static final int BATCH_SIZE = 100;

    private final JdbcKanbanRepository repository;
    private final PubSubPublisher publisher;
    private final Clock clock;
    private final String workerId;

    public OutboxWorker(JdbcKanbanRepository repository, PubSubPublisher publisher) {
        this(repository, publisher, Clock.systemUTC(), UUID.randomUUID().toString());
    }

    OutboxWorker(JdbcKanbanRepository repository, PubSubPublisher publisher, Clock clock, String workerId) {
        this.repository = Objects.requireNonNull(repository);
        this.publisher = Objects.requireNonNull(publisher);
        this.clock = Objects.requireNonNull(clock);
        this.workerId = Objects.requireNonNull(workerId);
    }

    @Override
    public void run() {
        for (int processed = 0; processed < BATCH_SIZE; processed++) {
            final long now = clock.millis();
            final var claimed = repository.claimNext(workerId, now, now + LEASE_MILLIS);
            if (claimed.isEmpty()) {
                return;
            }
            deliver(claimed.orElseThrow());
        }
    }

    private void deliver(OutboxEntry entry) {
        try {
            publisher.publish(entry);
            repository.markDelivered(entry.id(), workerId, clock.millis());
        } catch (Exception exception) {
            final long delay = Math.min(300_000L, 1_000L << Math.min(entry.attempts(), 8));
            Log.warn("Unable to deliver Kanban outbox item {}. Retrying later.", entry.id(), exception);
            repository.markFailed(entry.id(), workerId, clock.millis() + delay, exception.toString());
        }
    }
}
