package org.igniterealtime.openfire.plugins.kanban.muc;

import java.util.List;

import org.igniterealtime.openfire.plugins.kanban.model.Member;
import org.igniterealtime.openfire.plugins.kanban.model.Role;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.muc.Affiliation;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatManager;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

/**
 * Openfire-backed board MUC provisioner.
 *
 * Affiliation mapping (Solstice v1 profile):
 * <ul>
 *   <li>owner → MUC owner</li>
 *   <li>editor → MUC member</li>
 *   <li>viewer → MUC member (members-only rooms require membership to join/read history;
 *       dedicated no-voice moderation is out of scope for this cut)</li>
 * </ul>
 */
public final class OpenfireBoardDiscussionProvisioner implements BoardDiscussionProvisioner {
    private static final Logger Log = LoggerFactory.getLogger(OpenfireBoardDiscussionProvisioner.class);

    private final String componentBareJid;

    public OpenfireBoardDiscussionProvisioner(String componentBareJid) {
        this.componentBareJid = componentBareJid;
    }

    @Override
    public String ensureRoom(String boardId, String boardName, List<Member> members) {
        final MultiUserChatService mucService = resolveMucService();
        final String roomName = BoardDiscussionRooms.localpart(boardId);
        final String roomJid = roomName + "@" + mucService.getServiceDomain();
        MUCRoom room = mucService.getChatRoom(roomName);
        if (room == null) {
            try {
                room = mucService.getChatRoom(roomName, new JID(componentBareJid));
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to create discussion room " + roomJid, exception);
            }
            if (room == null) {
                throw new IllegalStateException("Unable to create discussion room " + roomJid);
            }
            room.setNaturalLanguageName(boardName == null || boardName.isBlank() ? roomName : boardName);
            room.setDescription("Kanban board discussion for " + boardId);
            room.setPersistent(true);
            room.setPublicRoom(false);
            try {
                room.setMembersOnly(true, Affiliation.owner, new JID(componentBareJid));
            } catch (Exception exception) {
                Log.warn("Unable to set members-only on {}", roomJid, exception);
            }
            room.setCanOccupantsChangeSubject(false);
            room.setLogEnabled(true);
            try {
                room.saveToDB();
                room.unlock(Affiliation.owner);
            } catch (Exception exception) {
                Log.warn("Unable to persist MUC room {}", roomJid, exception);
            }
        }
        syncAffiliations(roomJid, members);
        return roomJid;
    }

    @Override
    public String postCardRootMessage(String roomJid, String cardId, String cardTitle) {
        final String messageId = BoardDiscussionRooms.rootMessageId(cardId);
        final JID roomAddress = new JID(roomJid);
        final MultiUserChatService mucService = XMPPServer.getInstance().getMultiUserChatManager()
            .getMultiUserChatService(roomAddress);
        final MUCRoom room = mucService == null ? null : mucService.getChatRoom(roomAddress.getNode());
        if (room == null) {
            Log.warn("Discussion room {} not found; thread {} for card {} recorded without posting a root message",
                roomJid, messageId, cardId);
            return messageId;
        }
        try {
            final Message message = new Message();
            message.setID(messageId);
            message.setType(Message.Type.groupchat);
            message.setThread(messageId);
            message.setFrom(room.getJID());
            message.setBody("Card: " + (cardTitle == null ? cardId : cardTitle));
            room.broadcast(message);
        } catch (RuntimeException exception) {
            Log.warn("Unable to post discussion root message for card {} in {}", cardId, roomJid, exception);
        }
        return messageId;
    }

    @Override
    public void syncAffiliations(String roomJid, List<Member> members) {
        final JID roomAddress = new JID(roomJid);
        final MultiUserChatService mucService = XMPPServer.getInstance().getMultiUserChatManager()
            .getMultiUserChatService(roomAddress);
        if (mucService == null) {
            Log.warn("No MUC service for {}", roomJid);
            return;
        }
        final MUCRoom room = mucService.getChatRoom(roomAddress.getNode());
        if (room == null) {
            Log.warn("Discussion room missing: {}", roomJid);
            return;
        }
        for (Member member : members) {
            try {
                final JID bare = new JID(member.bareJid());
                if (member.role() == Role.OWNER) {
                    room.addOwner(bare, Affiliation.owner);
                } else {
                    // editors and viewers: member (join + history on members-only rooms)
                    room.addMember(bare, null, Affiliation.owner);
                }
            } catch (Exception exception) {
                Log.warn("Unable to sync affiliation for {} on {}", member.bareJid(), roomJid, exception);
            }
        }
    }

    private static MultiUserChatService resolveMucService() {
        final MultiUserChatManager manager = XMPPServer.getInstance().getMultiUserChatManager();
        MultiUserChatService mucService = manager.getMultiUserChatService("conference");
        if (mucService == null) {
            final List<MultiUserChatService> services = manager.getMultiUserChatServices();
            if (services.isEmpty()) {
                throw new IllegalStateException("No MultiUserChatService is configured");
            }
            mucService = services.get(0);
            Log.info("Using MUC subdomain '{}' for board discussion rooms", mucService.getServiceName());
        }
        return mucService;
    }
}
