package org.igniterealtime.openfire.plugins.kanban.service;

public final class KanbanException extends RuntimeException {
    public enum Code {
        BAD_REQUEST,
        FORBIDDEN,
        ITEM_NOT_FOUND,
        REVISION_CONFLICT,
        INVALID_COLUMN,
        INVALID_POSITION,
        WIP_LIMIT_EXCEEDED,
        DUPLICATE_MEMBER,
        FINAL_OWNER
    }

    private final Code code;
    private final Long currentRevision;

    public KanbanException(Code code, String message) {
        this(code, message, null);
    }

    public KanbanException(Code code, String message, Long currentRevision) {
        super(message);
        this.code = code;
        this.currentRevision = currentRevision;
    }

    public Code code() {
        return code;
    }

    public Long currentRevision() {
        return currentRevision;
    }
}
