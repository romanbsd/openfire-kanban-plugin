package org.igniterealtime.openfire.plugins.kanban.model;

public record KanbanColumn(String id, String boardId, String name, String rank, int wipLimit, long createdAt)
    implements Identified {}
