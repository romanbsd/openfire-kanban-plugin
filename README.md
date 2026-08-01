# Openfire Kanban Plugin

An Openfire-native, server-authoritative Kanban service exposed as the internal
component `kanban.<domain>`.

## Milestone 0 features

- board, column, card, and member persistence on PostgreSQL and HSQLDB
- IQ create/read/update/move/delete operations in `urn:xmpp:kanban:commands:0`
- optimistic board/card revisions and role-based authorization
- WIP limits and server-owned LexoRank ordering
- immutable activity records and ordered transactional PubSub outbox delivery
- persistent whitelist PubSub nodes for card snapshots and activity events
- Admin Console settings, diagnostics, retention, and manual outbox retry

## Requirements

- Java 17 or newer
- Maven 3.9 or newer
- Openfire 5.1.1 or newer

## Build

```shell
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn clean verify
```

The installable archive is `target/kanban-openfire-plugin-assembly.jar`. The
build runs Error Prone with warnings as errors, JSP compilation, HSQLDB-backed
tests, and JaCoCo coverage checks over the portable domain/protocol core.

## Install

Upload the generated plugin JAR from **Openfire Admin Console → Plugins**, or
copy it into Openfire's `plugins` directory. Configuration and outbox
diagnostics are under **Server → Server Settings → Kanban**.

## Protocol profile

Clients discover and address `kanban.<domain>`. Authoritative reads use
`list-boards` and `get-board`; mutations use IQ `set` and always carry
`expected-revision`. Successful mutation replies report `board-revision` and
the affected entity ID/revision. Snapshot replies identify the actual PubSub
service and the two board node IDs.

See [the implementation design](docs/openfire-kanban-plugin-design-revised.md),
[the draft ProtoXEP](docs/protoxep-xmpp-kanban.md), and
[the protocol gap analysis](docs/protoxep-gap-analysis.md).
