package org.igniterealtime.openfire.plugins.kanban.model;

import java.util.List;
import java.util.Objects;

public record Card(
    String id,
    String boardId,
    String columnId,
    long revision,
    String rank,
    String title,
    String description,
    String assigneeJid,
    CardPriority priority,
    List<String> labelIds,
    boolean deleted,
    String createdBy,
    long createdAt,
    long updatedAt,
    Long deletedAt
) implements Revisioned {
    public Card {
        Objects.requireNonNull(priority, "priority");
        labelIds = List.copyOf(labelIds);
    }

    public Card(
        String id, String boardId, String columnId, long revision, String rank, String title,
        String description, String assigneeJid, boolean deleted, String createdBy,
        long createdAt, long updatedAt, Long deletedAt
    ) {
        this(id, boardId, columnId, revision, rank, title, description, assigneeJid,
            CardPriority.NONE, List.of(), deleted, createdBy, createdAt, updatedAt, deletedAt);
    }

    public Card update(
        String updatedTitle, String updatedDescription, String updatedAssigneeJid,
        CardPriority updatedPriority, List<String> updatedLabelIds, long now
    ) {
        return new Card(id, boardId, columnId, revision + 1, rank, updatedTitle, updatedDescription,
            updatedAssigneeJid, updatedPriority, updatedLabelIds, false, createdBy, createdAt, now, null);
    }

    public Card moveTo(String targetColumnId, String targetRank, long now) {
        return new Card(id, boardId, targetColumnId, revision + 1, targetRank, title, description,
            assigneeJid, priority, labelIds, false, createdBy, createdAt, now, null);
    }

    public Card tombstone(long now) {
        return new Card(id, boardId, columnId, revision + 1, rank, title, description, assigneeJid,
            priority, labelIds, true, createdBy, createdAt, now, now);
    }
}
