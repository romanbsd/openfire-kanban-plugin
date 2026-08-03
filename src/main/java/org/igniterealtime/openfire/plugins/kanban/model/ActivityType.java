package org.igniterealtime.openfire.plugins.kanban.model;

/** Stable event type names persisted in activity history and published over PubSub. */
public enum ActivityType {
    BOARD_CREATED("BoardCreated"),
    COLUMN_CREATED("ColumnCreated"),
    CARD_CREATED("CardCreated"),
    CARD_UPDATED("CardUpdated"),
    CARD_MOVED("CardMoved"),
    CARD_DELETED("CardDeleted"),
    LABEL_CREATED("LabelCreated"),
    LABEL_UPDATED("LabelUpdated"),
    LABEL_DELETED("LabelDeleted"),
    MEMBER_ADDED("MemberAdded"),
    MEMBER_ROLE_CHANGED("MemberRoleChanged"),
    MEMBER_REMOVED("MemberRemoved");

    private final String wireName;

    ActivityType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
