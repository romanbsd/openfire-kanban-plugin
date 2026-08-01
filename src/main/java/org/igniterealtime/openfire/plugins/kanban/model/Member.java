package org.igniterealtime.openfire.plugins.kanban.model;

public record Member(String id, String boardId, String bareJid, Role role, long createdAt) implements Identified {}
