package ru.agimate.controlapi.service.runcontext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.service.seed.PromptTexts;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How an event without a prompt channel reaches the model: the trigger's guidance, then its main
 * block — trusted text for a {@code PROMPT} declaration, untrusted data otherwise. One rendering for
 * both ways an event gets in: the run it starts ({@code RunContextService}) and the running run that
 * absorbs it by steering ({@code SteeringService}); two copies would let a steered report reach the
 * model with less caution than the same report arriving on its own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerBlocks {

    /** Deterministic serialisation of an event (sorted keys) — the same block whatever the map's order. */
    private static final ObjectMapper EVENT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final ConnectorRegistry connectorRegistry;
    private final PromptTexts promptTexts;

    /** For a caller outside context assembly: the directives are read from the trigger's declaration. */
    public List<RunBlock> of(Trigger trigger) {
        ContextDirectives directives = connectorRegistry
                .findCapability(trigger.connectorCode(), TriggerProvider.class)
                .map(TriggerProvider::getTriggers)
                .map(triggers -> triggers.get(trigger.name()))
                .map(TriggerSpec::context)
                .orElse(null);
        return of(trigger, EffectiveContext.of(ContextSpec.SYSTEM_TRIGGER, directives));
    }

    List<RunBlock> of(Trigger trigger, EffectiveContext effective) {
        List<RunBlock> blocks = new ArrayList<>(2);
        if (effective.guidance() != null) {
            String guidance = promptTexts.triggerGuidance(
                    trigger.connectorCode(), trigger.name(), effective.guidance());
            blocks.add(RunBlock.trusted("event_guidance",
                    "connector:" + trigger.connectorCode(), guidance, Map.of()));
        }
        blocks.add(mainBlock(effective, trigger));
        return blocks;
    }

    /**
     * The event's main block, per {@link EffectiveContext#presentation()}: {@code PROMPT} means
     * trusted text from {@code data[promptParam]} (declarable by internal connectors only, guarded at
     * bootstrap; the text is authored by the agent or the platform), and empty or non-string falls
     * back to the untrusted event.
     */
    private static RunBlock mainBlock(EffectiveContext effective, Trigger trigger) {
        if (effective.presentation() != ContextDirectives.Presentation.PROMPT) {
            return eventBlock(trigger);
        }
        Object raw = trigger.data() != null && effective.promptParam() != null
                ? trigger.data().get(effective.promptParam()) : null;
        if (raw instanceof String text && !text.isBlank()) {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("connector", trigger.connectorCode());
            attrs.put("name", trigger.name());
            return RunBlock.trusted("trigger_prompt", "connector:" + trigger.connectorCode(),
                    text.strip(), attrs);
        }
        log.warn("PROMPT trigger {}.{} has no usable '{}' in data — falling back to event block",
                trigger.connectorCode(), trigger.name(), effective.promptParam());
        return eventBlock(trigger);
    }

    /** The event as data: an untrusted block, with the wrapper and preamble applied by the worker's renderer. */
    public static RunBlock eventBlock(Trigger trigger) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("connectorCode", trigger.connectorCode());
        event.put("connectionId", trigger.connectionId());
        event.put("name", trigger.name());
        event.put("id", trigger.id());
        event.put("data", trigger.data());
        event.put("occurredAt", trigger.occurredAt());
        String content;
        try {
            content = EVENT_MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            content = String.valueOf(event);
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("connector", trigger.connectorCode());
        attrs.put("name", trigger.name());
        return new RunBlock("event", "connector:" + trigger.connectorCode(), content, attrs, false, false);
    }
}
