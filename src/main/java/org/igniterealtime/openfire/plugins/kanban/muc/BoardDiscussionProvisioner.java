package org.igniterealtime.openfire.plugins.kanban.muc;

import java.util.List;

import org.igniterealtime.openfire.plugins.kanban.model.Member;

/**
 * Provisions the board discussion MUC and posts the XEP-0461 root message for a card.
 * Implementations may be fakes in unit tests.
 */
public interface BoardDiscussionProvisioner {
    BoardDiscussionProvisioner NOOP = new BoardDiscussionProvisioner() {
        @Override
        public String ensureRoom(String boardId, String boardName, List<Member> members) {
            throw new UnsupportedOperationException("Board discussion provisioner is not configured");
        }

        @Override
        public String postCardRootMessage(String roomJid, String cardId, String cardTitle) {
            throw new UnsupportedOperationException("Board discussion provisioner is not configured");
        }

        @Override
        public void syncAffiliations(String roomJid, List<Member> members) {
            // no-op
        }
    };

    /** Returns the bare MUC room JID (localpart@conference.domain). */
    String ensureRoom(String boardId, String boardName, List<Member> members);

    /** Posts a stable root body and returns the message id used as the XEP-0461 thread id. */
    String postCardRootMessage(String roomJid, String cardId, String cardTitle);

    void syncAffiliations(String roomJid, List<Member> members);
}
