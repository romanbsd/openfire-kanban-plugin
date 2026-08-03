package org.igniterealtime.openfire.plugins.kanban.model;

public record Label(String id, String boardId, String name, LabelColor color, long createdAt) implements Identified {}
