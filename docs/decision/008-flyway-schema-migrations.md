# ADR-008: Flyway for Schema Migrations

**Status:** Accepted

## Context

Schema so far has been managed by `hibernate.ddl-auto=update`: Hibernate diffs each `@Entity`
against the live database at startup and issues whatever `ALTER`/`CREATE` statements it thinks
are needed. That has been enough to get through Phases 1/2/3/5 because every change so far has
been additive (new nullable column, new table) — exactly the subset `update` handles safely.

Two things make that no longer sufficient:

- `update` only ever adds. It never drops a column, narrows a type, or adds a `NOT NULL`/`CHECK`
  constraint to an existing column — the kind of change a real migration handles by rewriting
  existing rows or failing loudly, not by silently doing nothing. There's also no history: two
  environments can drift with no record of what ran where or when.
- Phase 4 (RAG) and Phase 6 (BYOK) are both coming up and both need more than "add a column":
  Phase 4 plausibly needs a Postgres extension (`pgvector`) and vector-typed columns; Phase 6's
  key storage needs encrypted columns and constraints on existing tables, per the roadmap's own
  note that it's "its own security surface worth an ADR." Retrofitting a migration tool is far
  cheaper now, with 3 tables and no destructive history to reconcile, than after either lands.

## Decision

Adopt Flyway (`flyway-core` + `flyway-database-postgresql`), switch
`spring.jpa.hibernate.ddl-auto` from `update` to `validate`, and check in
`src/main/resources/db/migration/V1__baseline.sql` capturing the schema exactly as it already
exists in every environment today (`conversations`, `messages`, `users` — ADR-001, ADR-007).

Existing databases (local dev, anything already provisioned) are **not** replayed against V1 —
`spring.flyway.baseline-on-migrate=true` + `baseline-version=1` tells Flyway to record V1 as
already applied on any database it finds without a `flyway_schema_history` table, rather than
running it. Only a genuinely empty database (fresh clone, CI) executes V1 for real. Either way,
every environment converges on the same `flyway_schema_history` state, and every schema change
from here on is a new `Vn__description.sql` file, reviewed and merged like any other code change.

## Rationale

- **`validate`, not `none`**: Hibernate still checks entities against the schema Flyway created,
  so an `@Entity` that drifts from its migration fails fast at startup instead of silently
  reading/writing the wrong columns.
- **`baseline-on-migrate` over hand-editing `flyway_schema_history`**: this is Flyway's documented
  path for adopting it mid-project, and keeps V1 accurate as real, executable DDL (verified by
  actually creating a fresh database from it) rather than a comment-only placeholder.
- **One migration per change, not one growing baseline**: keeps a reviewable history and avoids
  the exact problem being solved — undocumented, unreconstructable schema drift.

## Consequences

- Every future schema change is a new migration file, applied automatically on next startup
  (`spring-boot-starter-data-jpa`'s Flyway autoconfiguration runs it ahead of Hibernate's
  validation) — no more editing an `@Entity` and expecting the database to catch up on its own.
- `docker compose up -d` still produces a database Flyway can migrate into from empty; no infra
  change needed.
