package org.igniterealtime.openfire.plugins.kanban.model;

public record Board(
    String id, String name, long revision, String createdBy, long createdAt, long updatedAt, String discussionRoomJid
) implements Revisioned {
    public Board(String id, String name, long revision, String createdBy, long createdAt, long updatedAt) {
        this(id, name, revision, createdBy, createdAt, updatedAt, null);
    }

    public Board withDiscussionRoom(String roomJid) {
        return new Board(id, name, revision, createdBy, createdAt, updatedAt, roomJid);
    }

    public Board withRevision(long nextRevision, long now) {
        return new Board(id, name, nextRevision, createdBy, createdAt, now, discussionRoomJid);
    }
}
