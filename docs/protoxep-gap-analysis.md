# ProtoXEP Gap Analysis for the Openfire Reference Implementation

This document records places where `protoxep-xmpp-kanban.md` does not yet
define enough wire or behavioral detail for interoperable implementations. It
also records the provisional choices made by this plugin. These choices are an
implementation profile, not claims that the current draft already mandates
them.

## Commands and authoritative reads

The command list omits column operations even though columns are a core model,
and it defines neither board enumeration nor an authoritative snapshot command.
The reference profile adds `create-column`, `list-boards`, and `get-board`.
Board update/delete, comments, and attachments remain deferred.

The draft's reconnect algorithm says to fetch snapshots but does not say from
which entity or namespace. `get-board` therefore returns board metadata,
ordered columns, live cards, members, and the actual PubSub service/node IDs in
one authoritative response.

## Revision ownership and replies

“Every mutation” requiring `expected-revision` is ambiguous for creation, and
the draft has both board and card revisions without assigning mutations to a
revision domain. This profile uses zero for board creation, the board revision
for structure/membership/card creation, and the card revision for card patch,
move, and deletion. Card mutation does not also increment the board revision.

`<result revision="18"/>` cannot distinguish board revision, card revision, or
the affected entity. Results therefore contain `board-revision`, `id`, and an
entity `revision` where applicable. Conflict details are children in
`urn:xmpp:kanban:0` inside a normal XMPP stanza error and include
`current-revision`.

## Ordering and WIP limits

The draft recommends fractional indexing or LexoRank but does not define who
owns rank values, anchor semantics, exhaustion, or rebalancing. The server owns
all ranks. `move-card` accepts at most one of `before-card` and `after-card`,
validates that the anchor is a live card in the destination column, and uses an
internal fixed-width base-36 LexoRank. Exhausted intervals trigger a
transactional column rebalance.

WIP limit semantics and race handling are unspecified. This profile treats
zero as unlimited, counts only live cards, does not count a same-column move as
an addition, and checks the limit while holding the board mutation lock.

## Membership and authorization

Roles are defined but membership commands and the meaning of “Create” are not.
This profile adds member add/update/remove commands. Owners can manage members,
create/move/update/delete cards, and create columns. Editors can create,
update, and move cards and create columns, but cannot delete cards or manage
members. Viewers and guests are read-only because comments are deferred. The
last owner cannot be demoted or removed.

The draft does not state whether remote users are supported or how senders and
member JIDs are normalized. Milestone 0 accepts authenticated local senders
only and canonicalizes assignee and membership identifiers to bare JIDs.
Federation is deferred.

## PubSub topology and access

The draft does not identify the PubSub service JID and risks implying that the
Kanban component itself hosts XEP-0060. Snapshot responses now identify the
real `pubsub.<domain>` service and the cards/activity node IDs. Nodes are
persistent, whitelist-access, server-published, and retain their most recent
items. Clients do not publish directly.

Openfire 5.1 has no `member` node affiliation. The adapter uses `publisher` as
the whitelist affiliation while configuring `publish_model=owners`; the
component owner is the only publisher, so membership grants access without
granting client write authority. This Openfire-specific mapping should not be
copied into the interoperable text.

The draft also leaves node creation timing, membership grant/revoke behavior,
card deletion notification, and board/column/member synchronization undefined.
The reference implementation creates nodes lazily from ordered outbox work,
publishes a deleted card tombstone snapshot plus immutable `CardDeleted`
activity, publishes activity for non-card changes, and updates both node
affiliations for membership changes.

## Transaction and delivery semantics

The transaction flow says to publish after commit and before replying, while
the outbox section says synchronization is eventual. Waiting for PubSub would
make command success depend on a secondary projection. This implementation
replies after the database transaction commits; a background leased worker
publishes afterward. It preserves per-board order, retries with exponential
backoff capped at five minutes, and reclaims expired publishing leases.

The draft does not define event sequence numbers, replay cursors, retention, or
idempotency. The implementation maintains a monotonic per-board activity
sequence and deterministic outbox ordering, but the sequence is not yet exposed
as a standardized client replay token. Event and delivered-outbox retention is
configurable (90 days by default).

## Remaining protocol work

Comments and attachments appear in requirements and command lists but their
payloads, revisions, authorization, node items, and deletion semantics are not
specified. Error conditions also need a complete mapping to standard stanza
conditions. Board lifecycle, column update/delete, validation limits, direct
subscription rules, event schemas, item IDs, time format, disco item format,
and multi-device recovery cursors all require normative definitions before the
draft can support independent interoperable implementations.
