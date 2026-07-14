package ru.agimate.controlapi.grpc.mapper;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;
import ru.agimate.controlapi.service.runcontext.RunBlock;
import ru.agimate.controlapi.service.runcontext.RunHistoryMessage;
import ru.agimate.controlapi.service.runcontext.RunTool;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.HistoryMessage;
import ru.agimate.agentworker.PromptBlock;
import ru.agimate.agentworker.ToolAnnotations;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.nullToEmpty;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.toJsonBytes;

/**
 * Маппинг частей контекста рана: домен {@code service.runcontext} → proto {@code RunContext}.
 * Собирается в {@code AgentContextGrpcService.getRunContext}.
 */
@UtilityClass
public class RunContextMapper {

    public static PromptBlock toProto(RunBlock block) {
        return PromptBlock.newBuilder()
                .setName(nullToEmpty(block.name()))
                .setSource(nullToEmpty(block.source()))
                .setContent(nullToEmpty(block.content()))
                .putAllAttrs(block.attrs())
                .setTrusted(block.trusted())
                .setEphemeral(block.ephemeral())
                .build();
    }

    public static ConnectorToolSpec toProto(RunTool tool) {
        var spec = tool.spec();
        ConnectorToolSpec.Builder builder = ConnectorToolSpec.newBuilder()
                .setName(nullToEmpty(spec.name()))
                .setConnectionId(nullToEmpty(tool.connectionId()))
                .setNamespace(nullToEmpty(tool.namespace()))
                .setConnectorCode(nullToEmpty(tool.connectorCode()));
        if (spec.title() != null) {
            builder.setTitle(spec.title());
        }
        if (spec.description() != null) {
            builder.setDescription(spec.description());
        }
        if (spec.inputSchema() != null) {
            builder.setInputSchema(toJsonBytes(spec.inputSchema()));
        }
        if (spec.outputSchema() != null) {
            builder.setOutputSchema(toJsonBytes(spec.outputSchema()));
        }
        var annotations = spec.annotations() != null ? spec.annotations() : ToolAnnotationsSpec.DEFAULT;
        builder.setAnnotations(ToolAnnotations.newBuilder()
                .setReadOnlyHint(annotations.readOnlyHint())
                .setDestructiveHint(annotations.destructiveHint())
                .setIdempotentHint(annotations.idempotentHint())
                .setOpenWorldHint(annotations.openWorldHint())
                .build());
        if (spec.meta() != null) {
            builder.putAllMeta(spec.meta());
        }
        return builder.build();
    }

    public static HistoryMessage toProto(RunHistoryMessage message) {
        return HistoryMessage.newBuilder()
                .setKind(MessageKindMapper.toProto(message.kind()))
                .setText(nullToEmpty(message.text()))
                .build();
    }
}
