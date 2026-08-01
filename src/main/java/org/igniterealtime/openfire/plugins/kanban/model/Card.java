package org.igniterealtime.openfire.plugins.kanban.model;

public record Card(
    String id,
    String boardId,
    String columnId,
    long revision,
    String rank,
    String title,
    String description,
    String assigneeJid,
    boolean deleted,
    String createdBy,
    long createdAt,
    long updatedAt,
    Long deletedAt
) implements Revisioned {
    public Card update(String updatedTitle, String updatedDescription, String updatedAssigneeJid, long now) {
        return new Card(id, boardId, columnId, revision + 1, rank, updatedTitle, updatedDescription,
            updatedAssigneeJid, false, createdBy, createdAt, now, null);
    }

    public Card moveTo(String targetColumnId, String targetRank, long now) {
        return new Card(id, boardId, targetColumnId, revision + 1, targetRank, title, description,
            assigneeJid, false, createdBy, createdAt, now, null);
    }

    public Card tombstone(long now) {
        return new Card(id, boardId, columnId, revision + 1, rank, title, description, assigneeJid,
            true, createdBy, createdAt, now, now);
    }
}
