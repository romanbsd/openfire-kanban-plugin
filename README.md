# Openfire Kanban Plugin

An Openfire-native, server-authoritative Kanban service exposed as the internal
component `kanban.<domain>`.

## Milestone 0 features

- board, column, card, label, and member persistence on PostgreSQL and HSQLDB
- IQ create/read/update/move/delete operations in `urn:xmpp:kanban:commands:0`
- card priority + board label catalog (Solstice v1 profile)
- board MUC + per-card XEP-0461 discussion linkage via `ensure-card-discussion`
- optimistic board/card revisions and role-based authorization
- WIP limits and server-owned LexoRank ordering
- immutable activity records and ordered transactional PubSub outbox delivery
- persistent whitelist PubSub nodes for card snapshots and activity events
- Admin Console settings, diagnostics, retention, and manual outbox retry

## Requirements

- Java 17 or newer
- Maven 3.9 or newer
- Openfire 5.2.0 or newer (requires read-only PubSub `member` affiliations)

## Build

```shell
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn clean verify
```

The installable archive is `target/kanban.jar`. The
build runs Error Prone with warnings as errors, JSP compilation, HSQLDB-backed
tests, and JaCoCo coverage checks over the portable domain/protocol core.

## Docker end-to-end test

With the Openfire source tree checked out at `../Openfire`, run:

```shell
./integration/run-e2e.sh
```

The script builds Openfire from `../Openfire/Dockerfile`, installs the freshly
built Kanban plugin into a derived image, starts an isolated demo-boot server,
and drives the Admin Console, XMPP commands, service discovery, PubSub events
and access control, role changes, error paths, and persistence across an
Openfire restart. It removes the container and test volume afterward and writes
the server log to `target/docker-e2e-openfire.log`.

The Openfire image is expensive to build but reusable. For local iterations
after the first successful base build, use:

```shell
KANBAN_E2E_SKIP_BASE_BUILD=1 ./integration/run-e2e.sh
```

Ports, image/container names, Maven, and the Openfire checkout can be overridden
with the `KANBAN_E2E_*`, `OPENFIRE_*`, and `MAVEN` environment variables defined
at the top of `integration/run-e2e.sh`.

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

See [the implementation design](docs/openfire-kanban-plugin-design-revised.md)
and [the ProtoXEP with its Openfire reference profile](docs/protoxep-xmpp-kanban.md).
