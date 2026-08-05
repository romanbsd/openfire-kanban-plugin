package org.igniterealtime.openfire.plugins.kanban.muc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.igniterealtime.openfire.plugins.kanban.model.Member;

/** Deterministic in-process provisioner for unit tests and non-Openfire bootstraps. */
public final class InMemoryBoardDiscussionProvisioner implements BoardDiscussionProvisioner {
    public record PostedRoot(String roomJid, String cardId, String messageId, String title) {}

    private final String conferenceDomain;
    private final List<PostedRoot> postedRoots = new CopyOnWriteArrayList<>();
    private final List<String> syncedRooms = new CopyOnWriteArrayList<>();

    public InMemoryBoardDiscussionProvisioner(String xmppDomain) {
        this.conferenceDomain = "conference." + xmppDomain;
    }

    @Override
    public String ensureRoom(String boardId, String boardName, List<Member> members) {
        final String roomJid = BoardDiscussionRooms.roomJid(boardId, conferenceDomain);
        syncAffiliations(roomJid, members);
        return roomJid;
    }

    @Override
    public String postCardRootMessage(String roomJid, String cardId, String cardTitle) {
        final String messageId = BoardDiscussionRooms.rootMessageId(cardId);
        postedRoots.add(new PostedRoot(roomJid, cardId, messageId, cardTitle));
        return messageId;
    }

    @Override
    public void syncAffiliations(String roomJid, List<Member> members) {
        syncedRooms.add(roomJid);
    }

    public List<PostedRoot> postedRoots() {
        return List.copyOf(postedRoots);
    }

    public List<String> syncedRooms() {
        return new ArrayList<>(syncedRooms);
    }
}
