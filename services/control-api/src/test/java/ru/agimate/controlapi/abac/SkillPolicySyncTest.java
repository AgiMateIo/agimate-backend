package ru.agimate.controlapi.abac;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.agimate.controlapi.abac.SkillPolicySync.DesiredPolicy;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.AgentConnectionPolicy;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.model.ConnectorRequirement;
import ru.agimate.controlapi.database.model.ConnectorRequirement.Rule;
import ru.agimate.controlapi.database.model.ConnectorRequirement.Rules;
import ru.agimate.controlapi.database.repositories.AgentConnectionPolicyRepository;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SkillPolicySync — правила навыка как строки ABAC на привязке")
class SkillPolicySyncTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID BINDING_ID = UUID.randomUUID();
    private static final UUID SKILL_ID = UUID.randomUUID();

    private final AgentConnectionPolicyRepository policyRepository = mock(AgentConnectionPolicyRepository.class);
    private final AgentConnectionRepository agentConnectionRepository = mock(AgentConnectionRepository.class);
    private final ConnectionAccessEvaluator accessEvaluator = mock(ConnectionAccessEvaluator.class);

    private final SkillPolicySync sync = new SkillPolicySync(policyRepository, agentConnectionRepository, accessEvaluator);

    private final ConnectorRequirement allowList = new ConnectorRequirement("mcp", "docs", null, null,
            new Rules(List.of(new Rule("query-docs", null), new Rule("create_issue", Map.of("owner", "agimate"))), null),
            null);

    @BeforeEach
    void setUp() {
        when(agentConnectionRepository.findActiveBinding(AGENT_ID, CONNECTION_ID)).thenReturn(Optional.of(
                AgentConnection.builder().id(BINDING_ID).agentId(AGENT_ID).connectionId(CONNECTION_ID).build()));
        when(policyRepository.findActiveByAgentConnectionId(BINDING_ID)).thenReturn(List.of());
        when(policyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static AgentConnectionPolicy existing(String name, AccessEffect effect, String source) {
        return AgentConnectionPolicy.builder().id(UUID.randomUUID()).agentConnectionId(BINDING_ID)
                .kind(PolicyKind.TOOL).name(name).effect(effect).source(source).build();
    }

    private List<AgentConnectionPolicy> saved() {
        ArgumentCaptor<AgentConnectionPolicy> captor = ArgumentCaptor.forClass(AgentConnectionPolicy.class);
        verify(policyRepository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("перевод объявления в строки")
    class Desired {

        @Test
        @DisplayName("allow-список = DENY на всю привязку + ALLOW на каждое имя, params — фильтр строки")
        void allowListBecomesBindingWideDenyPlusAllows() {
            List<DesiredPolicy> rows = SkillPolicySync.desired(allowList);

            assertEquals(3, rows.size());
            assertEquals(new DesiredPolicy(PolicyKind.TOOL, null, AccessEffect.DENY, null), rows.get(0));
            assertEquals(new DesiredPolicy(PolicyKind.TOOL, "query-docs", AccessEffect.ALLOW, null), rows.get(1));
            assertEquals(new DesiredPolicy(PolicyKind.TOOL, "create_issue", AccessEffect.ALLOW, Map.of("owner", "agimate")),
                    rows.get(2));
        }

        @Test
        @DisplayName("deny-список = DENY на имя поверх дефолт-allow; triggers идут своим kind")
        void denyListIsTargetedDenies() {
            ConnectorRequirement requirement = new ConnectorRequirement("mcp", "mcp", null, null,
                    new Rules(null, List.of("delete_repository")),
                    new Rules(null, List.of("push")));

            assertEquals(List.of(
                    new DesiredPolicy(PolicyKind.TOOL, "delete_repository", AccessEffect.DENY, null),
                    new DesiredPolicy(PolicyKind.TRIGGER, "push", AccessEffect.DENY, null)),
                    SkillPolicySync.desired(requirement));
        }

        @Test
        @DisplayName("без правил — пусто")
        void noRulesNoRows() {
            assertTrue(SkillPolicySync.desired(ConnectorRequirement.of("mcp")).isEmpty());
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @Test
        @DisplayName("пустая привязка → строки создаются с source навыка, кэш решений сброшен")
        void createsRowsWithSource() {
            List<String> conflicts = sync.apply(AGENT_ID, CONNECTION_ID, SKILL_ID, allowList);

            assertTrue(conflicts.isEmpty());
            List<AgentConnectionPolicy> rows = saved();
            assertEquals(3, rows.size());
            assertTrue(rows.stream().allMatch(row -> SkillPolicySync.source(SKILL_ID).equals(row.getSource())));
            assertEquals(BINDING_ID, rows.get(0).getAgentConnectionId());
            verify(accessEvaluator).invalidateByAgent(AGENT_ID);
        }

        @Test
        @DisplayName("нет привязки коннекции к агенту → ничего не пишется, конфликтов нет")
        void noBindingNothingWritten() {
            when(agentConnectionRepository.findActiveBinding(AGENT_ID, CONNECTION_ID)).thenReturn(Optional.empty());

            assertTrue(sync.apply(AGENT_ID, CONNECTION_ID, SKILL_ID, allowList).isEmpty());
            verify(policyRepository, never()).save(any());
        }

        @Test
        @DisplayName("чужая строка на том же (kind, name) — конфликт, не трогается даже при том же эффекте")
        void foreignRowIsAConflict() {
            AgentConnectionPolicy users = existing(null, AccessEffect.DENY, null);
            AgentConnectionPolicy otherSkills = existing("query-docs", AccessEffect.ALLOW, SkillPolicySync.source(UUID.randomUUID()));
            when(policyRepository.findActiveByAgentConnectionId(BINDING_ID)).thenReturn(List.of(users, otherSkills));

            List<String> conflicts = sync.apply(AGENT_ID, CONNECTION_ID, SKILL_ID, allowList);

            assertEquals(List.of("TOOL/*", "TOOL/query-docs"), conflicts);
            List<AgentConnectionPolicy> rows = saved();
            assertEquals(1, rows.size(), "создана только create_issue");
            assertEquals("create_issue", rows.get(0).getName());
            assertNull(users.getDeletedAt());
            assertEquals(AccessEffect.DENY, users.getEffect());
        }

        @Test
        @DisplayName("своя строка обновляется, своя лишняя — снимается: повтор = сброс к правилам автора")
        void reapplyResetsToTheAuthor() {
            AgentConnectionPolicy mine = existing("query-docs", AccessEffect.DENY, SkillPolicySync.source(SKILL_ID));
            AgentConnectionPolicy stale = existing("old-tool", AccessEffect.ALLOW, SkillPolicySync.source(SKILL_ID));
            when(policyRepository.findActiveByAgentConnectionId(BINDING_ID)).thenReturn(List.of(mine, stale));

            sync.apply(AGENT_ID, CONNECTION_ID, SKILL_ID, allowList);

            assertEquals(AccessEffect.ALLOW, mine.getEffect(), "эффект приведён к объявлению");
            assertNull(mine.getDeletedAt());
            assertNotNull(stale.getDeletedAt(), "имени больше нет в объявлении");
        }

        @Test
        @DisplayName("conflicts — та же разница, но без записи")
        void conflictsIsReadOnly() {
            when(policyRepository.findActiveByAgentConnectionId(BINDING_ID))
                    .thenReturn(List.of(existing(null, AccessEffect.DENY, null)));

            assertEquals(List.of("TOOL/*"), sync.conflicts(AGENT_ID, CONNECTION_ID, SKILL_ID, allowList));
            verify(policyRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("remove снимает строки навыка на привязках этого агента")
    void removeRetiresOwnRows() {
        AgentConnectionPolicy mine = existing("query-docs", AccessEffect.ALLOW, SkillPolicySync.source(SKILL_ID));
        when(policyRepository.findActiveBySourceAndAgent(SkillPolicySync.source(SKILL_ID), AGENT_ID))
                .thenReturn(List.of(mine));

        sync.remove(AGENT_ID, SKILL_ID);

        assertNotNull(mine.getDeletedAt());
        verify(policyRepository, times(1)).saveAll(List.of(mine));
        verify(accessEvaluator).invalidateByAgent(AGENT_ID);
    }
}
