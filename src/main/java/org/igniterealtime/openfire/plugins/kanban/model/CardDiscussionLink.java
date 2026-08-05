package org.igniterealtime.openfire.plugins.kanban.model;

/** Result of {@code ensure-card-discussion}. */
public record CardDiscussionLink(
    String cardId, String discussionRoomJid, String discussionThreadId, long cardRevision, long boardRevision
) {}
