package org.igniterealtime.openfire.plugins.kanban.model;

public record Board(String id, String name, long revision, String createdBy, long createdAt, long updatedAt)
    implements Revisioned {}
