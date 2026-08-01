package org.igniterealtime.openfire.plugins.kanban.outbox;

import org.igniterealtime.openfire.plugins.kanban.repository.JdbcKanbanRepository.OutboxEntry;

@FunctionalInterface
public interface PubSubPublisher {
    void publish(OutboxEntry entry) throws Exception;
}
