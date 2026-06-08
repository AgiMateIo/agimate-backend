# Refactoring: single UUID Primary Key (drop Long id + pub_id duality)

## Motivation

control-api entities currently carry **two identifiers**:

- `Long id` — `BIGSERIAL` primary key, internal-only;
- `UUID pubId` — external-facing identifier (`UUIDUtils.generateUUIDv8()`).

This forces a permanent `pubId → Long` resolution layer and has produced **three inconsistent
FK conventions** across the schema (see audit). The result is ~406 `pubId` references across
81 files and SQL that joins `a.pub_id = p.agent_pub_id` in one place and `a.id = b.agent_id`
in another.

**Goal:** every entity has a single `UUID id` primary key, and every internal FK references
that UUID. `pubId` disappears.

## Decisions (agreed)

1. **Generation strategy: native `uuidv7()` from PostgreSQL 18** (`DEFAULT uuidv7()` on the
   `id` column). See §1 for the comparison and why this beats app-side generation here.
2. **All tables move to UUID PK, including high-volume log tables** (`tool_use_logs`,
   `trigger_logs`, `webhook_delivery_logs`, `channel_session_messages`).
3. **`Connector` is the only exception** — keeps `code TEXT` natural PK. Unchanged.
4. **`user_pub_id` stays `UUID`** — cross-service reference into user-api (out of scope). No change.
5. **`connector_code` stays `TEXT`** — FK to `connectors(code)`. No change.
6. DB is **not deployed to production** → Liquibase changelogs are edited in place (§6).
7. **Rename `*_pub_id` FK columns/fields → `*_id`** for consistency (the duality is gone).

---

## 1. Generation strategy: why DB-side `uuidv7()`

Environment check: the application DB already runs **`postgres:18-alpine`**
(`ops/local/docker-compose-postgres.yaml`), so the built-in RFC 9562 `uuidv7()` function is
available. `services/docker/docker-compose.yml` is still `postgres:17-alpine` and must be
bumped to 18 (and any CI / integration-test DB likewise).

Audit result that unlocks DB-side generation: **no code needs an entity id before it is
saved.** FK values come from the request (`data.agentPubId()`) or from already-loaded entities
(`agent.getPubId()`), never from a freshly-built, unsaved parent. `getPubId()` is only read
*after* `save()` (logging). So generation can move into the DB.

| Aspect | **DB-side `uuidv7()` (recommended)** | App-side (current `generateUUIDv8()` / a v7 lib) |
|---|---|---|
| Standard | RFC 9562 UUIDv7, native | bespoke v8 (CRC + hardcoded `"agimate"` secret) or extra dep |
| Custom code | none on the PK path | `UUIDUtils` stays in the PK path |
| `save()` behaviour | id is `null` pre-insert → Spring Data uses `persist()` cleanly | id non-null pre-insert → `merge()` (extra SELECT) unless `Persistable` workaround |
| Ordering | DB-guaranteed monotonic across all app instances | monotonic per-JVM (synchronized counter) |
| id known before flush | **no** (audited: not needed) | yes |
| Insert batching | `INSERT … RETURNING id`; Hibernate 6 batches on PG, slightly less optimal | full batching with `reWriteBatchedInserts=true` |
| Version constraint | **PG 18 everywhere** (app ✓, must bump test/CI/`services/docker`) | any PG version |

**Conclusion:** DB-side `uuidv7()` is cleaner — it deletes the homegrown CRC/secret UUID
machinery from the PK path, removes the `merge()`-vs-`persist()` gotcha, and gives
cross-instance monotonic ordering. The only real cost is a hard PG18 dependency, which the app
DB already satisfies.

`UUIDUtils.generateUUIDv8()` is **kept** for its non-PK uses: `RequestIdFilter` (request ids),
`AgentSessionMessagesService` (verify line 84 — confirm it is not setting a PK that now has a
DB default), and user-api entities. `validateUUIDv8()` is used only in `UUIDUtilsTest`, so
nothing in the runtime breaks.

### Entity mapping for a DB-generated UUID PK (Hibernate 6)

```java
@Id
@GeneratedValue                              // DB supplies the value
@ColumnDefault("uuidv7()")                   // org.hibernate.annotations.ColumnDefault
@Generated(event = EventType.INSERT)         // org.hibernate.annotations.Generated → INSERT ... RETURNING id
@Column(name = "id", updatable = false, nullable = false)
private UUID id;
```

Hibernate issues `INSERT … RETURNING id` and populates `id` after persist, so `saved.getId()`
works exactly where `saved.getPubId()` used to. Because `id` is `null` before insert, Spring
Data's `isNew()` is `true` and no `Persistable` workaround is required — `BaseEntity` does
**not** need changes for this strategy.

> If the team later prefers app-side generation, the alternative is documented in §7
> (Appendix); it requires implementing `Persistable<UUID>` in `BaseEntity` to avoid `merge()`.

---

## 2. FK convention audit (the mess being fixed)

| Convention today | Where | Target |
|---|---|---|
| `Long id` PK + `UUID pubId` | all 21 entities | single `UUID id` PK (DB-gen), drop `pubId` |
| `Long *Id` scalar FK | `agenticTeamId`, `channelId`, `sessionId`, `boardId`, `parentTaskId`, `createdByAgentId`, `assigneeAgentId`, `agentId` (ChannelSessionMessage, BoardTaskComment) | `UUID *Id` |
| `UUID *PubId` scalar FK | `agentPubId` ×5, `skillPubId`, `llmProviderPubId`, `parentPubId`, `sessionPubId` | rename → `UUID *Id` |
| JPA `@ManyToOne/@OneToOne/@JoinColumn` on `Long id` | `TriggerLogAgent.triggerLog` (trigger_log_id), `TriggerLogAgent.agent` (agent_id), `SkillConnector.skill` (skill_id), `Board.agenticTeam` (agentic_team_id, OneToOne), `WebhookDeliveryLog.triggerLogAgent` (trigger_log_agent_id); `TriggerLog.triggerLogAgents` OneToMany mappedBy | join column type → `UUID` automatically (Hibernate infers from target `@Id`); only migration column type changes |
| `TEXT` natural FK (`connector_code`) | apps, integration_credentials, policies, skill_connectors | unchanged |
| `UUID user_pub_id` (cross-service) | many | unchanged |

The `*_pub_id` rename is the riskiest mechanical part because it touches **native SQL** in
`AgentRepository` (`findAllowedAgents`, `findAllowedAgentsForTeamId`,
`findRoutableByUserPubIdAndTriggerName`, `findBySkillPubId`): joins like
`a.pub_id = p.agent_pub_id` → `a.id = p.agent_id`, `t.pub_id = :agenticTeamPubId` → `t.id`.

---

## 3. Execution order

### 3.1 `libs/common`
- `UUIDUtils` — **no change** (still used for request ids / non-PK).
- `BaseEntity` — **no change** under the DB-gen strategy.

### 3.2 Entities (21 files; `Connector` untouched)
- Replace `@Id @GeneratedValue Long id` + `UUID pubId` with the DB-generated `UUID id` mapping
  from §1.
- `Long *Id` FK fields → `UUID *Id`.
- `UUID *PubId` FK fields → `UUID *Id` (rename).
- JPA-relation entities: no field change; `@JoinColumn` resolves to UUID automatically.
- Drop the `pub_id` column/unique annotations; keep other unique constraints (e.g. `key_id`).

### 3.3 Repositories (~20 files)
- `JpaRepository<E, Long>` → `JpaRepository<E, UUID>`.
- Delete `findByPubId(...)` — callers switch to `findById(...)`.
- `softDelete(@Param("id") Long id ...)` → `UUID id`.
- Rewrite the four native `AgentRepository` queries (§2).
- Long FK method params (`findByUserPubIdAndAgenticTeamId`, `existsByAgenticTeamId`, …) → `UUID`.

### 3.4 Services
- Remove `pubId → Long` resolution; `repo.findByPubId(x)` → `repo.findById(x)`.
- `entity.getPubId()` → `entity.getId()`.
- FK assignments use UUIDs directly.

### 3.5 DTOs / responses / mappers / controllers
- Response builders: `getPubId()` → `getId()`.
- Verify no `Long` path/query params remain for entity ids.
- `TriggerMapper`, `ToolSpecificationMapper`: update id source.

### 3.6 Liquibase changelogs (edit in place)
Master uses `includeAll` over `initial/` then `updates/`. For every table except `connectors`:
- `id`: `BIGSERIAL autoIncrement` → `UUID` with `defaultValueComputed="uuidv7()"`, primary key.
- Remove the `pub_id` column and its unique index.
- FK columns `BIGINT` → `UUID`; rename `*_pub_id` → `*_id`.
- Drop redundant `pub_id` indexes; keep functional ones (`user_pub_id`, `key_id`, …).
- Fix the `updates/2026/**` changesets that add `BIGINT`/`*_pub_id` FK columns
  (channels, agent_pub_id, trigger-log-agent run registry, channel session messages).
- **Alternative:** squash into `initial/`. Recommend edit-in-place for a localized review diff.

### 3.7 Environments & tests
- Bump `services/docker/docker-compose.yml` Postgres `17 → 18`; ensure CI / any integration
  test DB is PG18 (required for `uuidv7()`).
- Confirm whether `@SpringBootTest` boots a DB; if so, point it at PG18.
- Update tests referencing `getPubId()` / Long ids; `*.http` ids are already UUID-shaped.
- `./gradlew :libs:common:test :control-api:test`.

### 3.8 Build & verify
- `./gradlew build`; boot control-api on a fresh schema (`ddl-auto: validate` must pass →
  entity UUID columns must match Liquibase exactly).
- Smoke-test agent create / trigger routing / channel flows (exercise the native queries).
- Update `/docs/services/` where id types are documented.

---

## 4. Risk register

| Risk | Mitigation |
|---|---|
| PG18-only `uuidv7()` not present in some env | bump `services/docker` + CI/test DB to PG18 before merge |
| Native SQL `*_pub_id` joins break silently | rewrite + boot-test ABAC/routing queries explicitly |
| `ddl-auto: validate` mismatch (UUID vs BIGINT) | full table-by-table checklist in §3.6; Hibernate fails fast at boot |
| `AgentSessionMessagesService` line 84 sets a PK that now has a DB default | verify; remove manual id assignment if it targets a DB-generated PK |
| `*PubId` rename ripples wider than expected | one pass; `grep -rn "PubId" control-api/src/main` → only `userPubId` left |
| Reduced insert batching on log tables | accepted; PG18 `INSERT … RETURNING` is adequate |

## 5. Done criteria
- `grep -rn "pubId\|pub_id" control-api/src/main` returns only `userPubId` / `user_pub_id`.
- No entity has a `Long id`; no repository is `JpaRepository<*, Long>`.
- `./gradlew :control-api:test` green; control-api boots on a fresh PG18 schema.

---

## 6. Entity → table → FK checklist (for execution)

| Entity | Table | Internal FK columns to convert | Notes |
|---|---|---|---|
| App | apps | — | connector_code TEXT, user_pub_id keep |
| IntegrationCredentials | integration_credentials | — | connector_code TEXT, user_pub_id keep |
| AgenticTeam | agentic_teams | — | user_pub_id keep |
| Agent | agents | agentic_team_id (BIGINT→UUID) | user_pub_id keep |
| AgentToolPolicy | agent_tool_policies | agent_pub_id→agent_id, channel_id (BIGINT→UUID) | |
| AgentTriggerPolicy | agent_trigger_policies | agent_pub_id→agent_id, channel_id (BIGINT→UUID) | native SQL |
| TriggerLog | trigger_logs | — | OneToMany to trigger_log_agents |
| TriggerLogAgent | trigger_log_agents | trigger_log_id, agent_id (BIGINT→UUID), session_pub_id→session_id, agent_pub_id→agent_id | @ManyToOne ×2 |
| WebhookDeliveryLog | webhook_delivery_logs | trigger_log_agent_id (BIGINT→UUID) | duration_ms stays Long (not a FK) |
| ToolUseLog | tool_use_logs | agent_pub_id→agent_id | user_pub_id keep |
| Board | boards | agentic_team_id (BIGINT→UUID) | @OneToOne unique |
| BoardTask | board_tasks | board_id, parent_task_id (self), created_by_agent_id, assignee_agent_id (BIGINT→UUID) | |
| BoardTaskComment | board_task_comments | board_task_id, agent_id (BIGINT→UUID) | |
| Skill | skills | parent_pub_id→parent_id (self) | user_pub_id keep |
| SkillConnector | skill_connectors | skill_id (BIGINT→UUID) | @ManyToOne; connector_code TEXT |
| AgentSkill | agent_skills | agent_pub_id→agent_id, skill_pub_id→skill_id | |
| Channel | channels | agent_pub_id→agent_id | |
| ChannelSession | channel_sessions | channel_id (BIGINT→UUID) | |
| ChannelSessionMessage | channel_session_messages | session_id, agent_id (BIGINT→UUID) | |
| AgentLlm | agent_llms | agent_pub_id→agent_id, llm_provider_pub_id→llm_provider_id | |
| LlmProvider | llm_providers | — | user_pub_id keep |
| Connector | connectors | — | **unchanged** (TEXT PK) |

---

## 7. Appendix — app-side generation alternative (not chosen)

If generation must stay in Java (e.g. PG18 cannot be guaranteed everywhere, or id-before-save
becomes required):

- Generate a standard UUIDv7 in Java (add a lib such as
  `com.fasterxml.uuid:java-uuid-generator` → `Generators.timeBasedEpochGenerator()`), or keep
  the existing monotonic `generateUUIDv8()`.
- Set `@Id @Builder.Default UUID id = …;` (no DB default).
- **Required:** make `BaseEntity implements Persistable<UUID>` with a `@Transient boolean isNew`
  flag flipped in `@PostLoad/@PostPersist`, otherwise `save()` runs `merge()` (extra SELECT)
  for every insert because the id is non-null before persist.
