package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.AgentStarter;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.AgentStarterRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.dto.agent.AgentStarters;
import ru.agimate.controlapi.service.dto.agent.AgentStarters.OwnStarter;
import ru.agimate.controlapi.service.dto.agent.AgentStarters.Starter;
import ru.agimate.controlapi.service.seed.ContentLanguageResolver;
import ru.agimate.controlapi.util.StarterTexts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The greeting and the conversation starters of an agent (docs/decisions/agent-greeting.md). The agent's
 * own starters are stored; a skill's are computed from the bindings that pass
 * {@link AgentSkillService#gate} — a starter of a skill the agent does not get would lead nowhere — and
 * stored only once hidden.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentStarterService {

    private final AgentStarterRepository agentStarterRepository;
    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final SkillRepository skillRepository;
    private final AgentSkillService agentSkillService;
    private final ContentLanguageResolver contentLanguageResolver;

    public AgentStarters get(UUID agentId, UUID userId) {
        return starters(ownedAgent(agentId, userId));
    }

    /**
     * Replaces the greeting and the agent's own list. A starter that keeps its {@code id} is updated in
     * place rather than recreated, so its row outlives every edit of the list.
     */
    @Transactional
    public AgentStarters save(UUID agentId, UUID userId, String greeting, List<OwnStarter> starters) {
        Agent agent = ownedAgent(agentId, userId);
        agent.setGreeting(StarterTexts.greeting(greeting, "greeting"));

        List<OwnStarter> entries = (starters == null ? List.<OwnStarter>of() : starters).stream()
                .filter(entry -> entry != null && entry.text() != null && !entry.text().isBlank())
                .map(entry -> new OwnStarter(entry.id(), entry.text().strip()))
                .toList();
        StarterTexts.starters(entries.stream().map(OwnStarter::text).toList(),
                StarterTexts.MAX_AGENT_STARTERS, "starters");

        Map<UUID, AgentStarter> existing = agentStarterRepository.findByAgentId(agentId).stream()
                .filter(AgentStarter::isAgentOwn)
                .collect(Collectors.toMap(AgentStarter::getId, Function.identity()));
        List<AgentStarter> kept = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            OwnStarter entry = entries.get(i);
            AgentStarter row = entry.id() == null
                    ? AgentStarter.builder().agentId(agentId).build()
                    : existing.remove(entry.id());
            if (row == null) {
                throw new BadRequestStatusException("Unknown starter id: " + entry.id());
            }
            row.setText(entry.text());
            row.setSortOrder(i);
            kept.add(row);
        }
        agentStarterRepository.deleteAll(existing.values());
        agentStarterRepository.saveAll(kept);
        agentRepository.save(agent);
        return starters(agent);
    }

    /**
     * Hides one skill starter. Idempotent; only a text the skill carries now may be hidden — otherwise
     * the endpoint would store whatever it is sent.
     */
    @Transactional
    public void hide(UUID agentId, UUID userId, UUID agentSkillId, String text) {
        ownedAgent(agentId, userId);
        AgentSkill binding = agentSkillRepository.findById(agentSkillId)
                .filter(b -> b.getAgentId().equals(agentId))
                .orElseThrow(() -> new NotFoundStatusException("Skill binding not found"));
        String key = text == null ? "" : text.strip();
        boolean carried = skillRepository.findById(binding.getSkillId())
                .filter(skill -> !skill.isDeleted())
                .map(skill -> skill.getStarters().contains(key))
                .orElse(false);
        if (!carried) {
            throw new NotFoundStatusException("The skill has no such starter");
        }
        agentStarterRepository.hideSkillStarter(agentId, agentSkillId, key);
    }

    /** Brings back every hidden skill starter of the agent. */
    @Transactional
    public void restoreHidden(UUID agentId, UUID userId) {
        ownedAgent(agentId, userId);
        int restored = agentStarterRepository.deleteSkillStarterStates(agentId);
        log.info("Restored {} hidden starter(s) of agent {}", restored, agentId);
    }

    /** A new agent takes the greeting and the starters of the preset it was created from. */
    @Transactional
    public void copyFromPreset(Agent agent, AgentPreset preset) {
        agent.setGreeting(preset.getGreeting());
        agentRepository.save(agent);
        insertOwn(agent.getId(), preset.getStarters());
    }

    /**
     * Agents created from the preset before it had a greeting or starters get them now. Called at the
     * moment the seed first fills the preset, never on every start: a user who erased both would
     * otherwise get them back with each deploy.
     */
    @Transactional
    public void adoptPreset(AgentPreset preset) {
        boolean defaultLanguage = preset.getLanguage() == contentLanguageResolver.defaultLanguage();
        List<Agent> agents = agentRepository.findPresetAgentsMissingStarters(
                preset.getName(), preset.getLanguage(), defaultLanguage);
        for (Agent agent : agents) {
            if (agent.getGreeting() == null && preset.getGreeting() != null) {
                agent.setGreeting(preset.getGreeting());
                agentRepository.save(agent);
            }
            if (!agentStarterRepository.existsByAgentIdAndAgentSkillIdIsNull(agent.getId())) {
                insertOwn(agent.getId(), preset.getStarters());
            }
        }
        log.info("Preset {}/{}: greeting and starters handed to {} existing agent(s)",
                preset.getLanguage(), preset.getName(), agents.size());
    }

    private void insertOwn(UUID agentId, List<String> texts) {
        List<AgentStarter> rows = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            rows.add(AgentStarter.builder().agentId(agentId).text(texts.get(i)).sortOrder(i).build());
        }
        agentStarterRepository.saveAll(rows);
    }

    private AgentStarters starters(Agent agent) {
        List<AgentStarter> rows = agentStarterRepository.findByAgentId(agent.getId());
        List<Starter> result = new ArrayList<>();
        rows.stream()
                .filter(AgentStarter::isAgentOwn)
                .sorted(Comparator.comparing(AgentStarter::getSortOrder).thenComparing(AgentStarter::getId))
                .forEach(row -> result.add(new Starter(row.getId(), null, null, row.getText())));

        Set<HiddenKey> hidden = rows.stream()
                .filter(row -> !row.isAgentOwn() && row.isHidden())
                .map(row -> new HiddenKey(row.getAgentSkillId(), row.getText()))
                .collect(Collectors.toSet());
        Set<UUID> delivered = agentSkillService.gate(agent.getId()).satisfied().keySet();
        // Ids are uuidv7: their order is the order the skills were bound in.
        List<AgentSkill> bindings = agentSkillRepository.findByAgentId(agent.getId()).stream()
                .filter(binding -> delivered.contains(binding.getSkillId()))
                .sorted(Comparator.comparing(AgentSkill::getId))
                .toList();
        Map<UUID, Skill> skills = new HashMap<>();
        if (!bindings.isEmpty()) {
            skillRepository.findByIdInNotDeleted(bindings.stream().map(AgentSkill::getSkillId).toList())
                    .forEach(skill -> skills.put(skill.getId(), skill));
        }
        for (AgentSkill binding : bindings) {
            Skill skill = skills.get(binding.getSkillId());
            if (skill == null) {
                continue;
            }
            String title = skill.getTitle() != null ? skill.getTitle() : skill.getName();
            for (String text : skill.getStarters()) {
                if (!hidden.contains(new HiddenKey(binding.getId(), text))) {
                    result.add(new Starter(null, binding.getId(), title, text));
                }
            }
        }
        return new AgentStarters(agent.getGreeting(), result);
    }

    private Agent ownedAgent(UUID agentId, UUID userId) {
        return agentRepository.findById(agentId)
                .filter(agent -> agent.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
    }

    private record HiddenKey(UUID agentSkillId, String text) {
    }
}
