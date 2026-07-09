package ru.agimate.agentworker.agent.context;

import ru.agimate.agentworker.AgentMemory;
import ru.agimate.agentworker.AgentSpec;
import ru.agimate.agentworker.SkillRef;
import ru.agimate.agentworker.SkillSpec;
import ru.agimate.agentworker.TeamContext;
import ru.agimate.agentworker.TeamMember;
import ru.agimate.agentworker.dto.Trigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * System-prompt assembly (the system-prompt part of the former {@code PromptBuilder}, itself a
 * port of {@code prompt.py}).
 *
 * <p>{@link #build} renders the agent/team/skills/memory context block that seeds a fresh
 * session; {@link #selectBatchSkills} deterministically matches skills to a trigger batch for
 * the SYSTEM_TRIGGER profile. All methods are pure and static.
 */
public final class SystemPromptBuilder {

    /**
     * Trigger-path system-prompt suffix (trusted instructions). Triggers are autonomous event
     * handling, not a dialogue: often the right outcome is to do nothing.
     */
    public static final String TRIGGER_GUIDANCE =
            "## Обработка триггеров\n"
            + "- Это автономная обработка внешних событий, а не диалог. Если по "
            + "событиям ничего делать не требуется — отвечать не обязательно; можно "
            + "ответить очень кратко, например: «Решено проигнорировать, действия не "
            + "требуются».\n"
            + "- Каждый вызов инструмента должен быть обоснован: вызывай инструмент "
            + "только когда событие действительно требует действия, и коротко поясняй "
            + "причину вызова.";

    private SystemPromptBuilder() {
    }

    /**
     * Render the system prompt. {@code skills} are listed (metadata only) so the model knows what
     * is available; {@code loadedSkills} are fully loaded skills whose {@code skill_md} body is
     * injected as trusted instructions (the trigger path loads matching skills; the dialogue path
     * lists everything and loads nothing). Non-empty {@code memory} is injected as a
     * {@code <memory version=...>} section right after the agent's own prompt.
     */
    public static String build(
            AgentSpec spec,
            TeamContext teamCtx,
            List<SkillRef> skills,
            List<SkillSpec> loadedSkills,
            AgentMemory memory) {

        List<String> parts = new ArrayList<>();

        List<String> agentBlock = new ArrayList<>();
        agentBlock.add("## Agent");
        agentBlock.add("- id: " + spec.getAgentId());
        if (!spec.getName().isBlank()) {
            agentBlock.add("- name: " + spec.getName());
        }
        if (!spec.getAgentType().isBlank()) {
            agentBlock.add("- type: " + spec.getAgentType());
        }
        if (!spec.getTeamId().isBlank()) {
            agentBlock.add("- team_id: " + spec.getTeamId());
        }
        parts.add(String.join("\n", agentBlock));

        if (!spec.getSystemPrompt().isBlank()) {
            parts.add(spec.getSystemPrompt().strip());
        }

        String memoryBlock = memoryBlock(memory);
        if (memoryBlock != null) {
            parts.add(memoryBlock);
        }

        if (teamCtx != null) {
            parts.add(teamBlock(teamCtx));
        }

        if (skills != null && !skills.isEmpty()) {
            parts.add(skillsBlock(skills));
        }

        if (loadedSkills != null) {
            parts.addAll(loadedSkillBlocks(loadedSkills));
        }

        return String.join("\n\n", parts);
    }

    private static String memoryBlock(AgentMemory memory) {
        if (memory == null || memory.getContent().isBlank()) {
            return null;
        }
        return "<memory version=" + memory.getVersion() + ">\n" + memory.getContent().strip() + "\n</memory>";
    }

    private static List<String> loadedSkillBlocks(List<SkillSpec> loadedSkills) {
        List<String> blocks = new ArrayList<>();
        for (SkillSpec s : loadedSkills) {
            String body = s.getSkillMd().strip();
            if (body.isEmpty()) {
                continue;
            }
            String header = s.getName().isBlank() ? "## Активный навык" : "## Активный навык: " + s.getName();
            blocks.add(header + "\n" + body);
        }
        return blocks;
    }

    private static String teamBlock(TeamContext teamCtx) {
        List<String> block = new ArrayList<>();
        String heading = teamCtx.getName().isBlank() ? teamCtx.getTeamId() : teamCtx.getName();
        block.add("## Team: " + heading);
        block.add("- id: " + teamCtx.getTeamId());
        if (!teamCtx.getName().isBlank()) {
            block.add("- name: " + teamCtx.getName());
        }
        if (!teamCtx.getDescription().isBlank()) {
            block.add("- description: " + teamCtx.getDescription());
        }
        if (!teamCtx.getMembersList().isEmpty()) {
            block.add("Members:");
            for (TeamMember m : teamCtx.getMembersList()) {
                StringBuilder line = new StringBuilder("- pub_id=").append(m.getPubId());
                if (!m.getName().isBlank()) {
                    line.append(", name=").append(m.getName());
                }
                if (!m.getDescription().isBlank()) {
                    line.append(", description=").append(m.getDescription());
                }
                block.add(line.toString());
            }
        }
        return String.join("\n", block);
    }

    private static String skillsBlock(List<SkillRef> skills) {
        List<String> block = new ArrayList<>();
        block.add("## Skills");
        for (SkillRef s : skills) {
            block.add("- skill_id: " + s.getSkillId());
            if (!s.getName().isBlank()) {
                block.add("  name: " + s.getName());
            }
            if (!s.getDescription().isBlank()) {
                block.add("  description: " + s.getDescription());
            }
            if (!s.getConnectorCodesList().isEmpty()) {
                block.add("  connector_codes: " + String.join(", ", s.getConnectorCodesList()));
            }
        }
        return String.join("\n", block);
    }

    /**
     * Union of per-trigger skill matches across {@code batch}, deduplicated by {@code skillId}
     * (first occurrence wins, order preserved). A skill matches a trigger when the trigger's
     * {@code connectorCode} is among its {@code connectorCodes}. Empty when no event matches
     * any skill.
     */
    public static List<SkillRef> selectBatchSkills(List<SkillRef> skills, List<Trigger> batch) {
        Map<String, SkillRef> byId = new LinkedHashMap<>();
        for (Trigger trigger : batch) {
            for (SkillRef skill : selectTriggerSkills(skills, trigger)) {
                byId.putIfAbsent(skill.getSkillId(), skill);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Skills that declare the trigger's connector. Order preserved. */
    private static List<SkillRef> selectTriggerSkills(List<SkillRef> skills, Trigger trigger) {
        List<SkillRef> matched = new ArrayList<>();
        for (SkillRef skill : skills) {
            if (skill.getConnectorCodesList().contains(trigger.connectorCode())) {
                matched.add(skill);
            }
        }
        return matched;
    }
}
