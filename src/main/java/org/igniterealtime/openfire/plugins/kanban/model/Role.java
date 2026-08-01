package org.igniterealtime.openfire.plugins.kanban.model;

public enum Role {
    OWNER,
    EDITOR,
    VIEWER,
    GUEST;

    public boolean canCreate() {
        return this == OWNER || this == EDITOR;
    }

    public boolean canEditCards() {
        return canCreate();
    }

    public boolean canDeleteCards() {
        return this == OWNER;
    }

    public boolean canManageMembers() {
        return this == OWNER;
    }
}
