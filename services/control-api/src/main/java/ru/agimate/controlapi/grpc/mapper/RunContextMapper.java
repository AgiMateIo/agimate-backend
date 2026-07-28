package ru.agimate.controlapi.grpc.mapper;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;
import ru.agimate.controlapi.service.runcontext.InboundPart;
import ru.agimate.controlapi.service.runcontext.RunBlock;
import ru.agimate.controlapi.service.runcontext.RunHistoryMessage;
import ru.agimate.controlapi.service.runcontext.RunTool;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.FilePart;
import ru.agimate.agentworker.HistoryMessage;
import ru.agimate.agentworker.PromptBlock;
import ru.agimate.agentworker.ToolAnnotations;
import ru.agimate.agentworker.ToolCallRec;
import ru.agimate.agentworker.ToolResultRec;
import ru.agimate.agentworker.ToolTurn;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.nullToEmpty;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.toJsonBytes;

/**
 * Mapping of the parts of a run's context: the domain {@code service.runcontext} → proto
 * {@code RunContext}. Assembled in {@code AgentContextGrpcService.getRunContext}.
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

    public static FilePart toProto(InboundPart part) {
        return FilePart.newBuilder()
                .setFileId(nullToEmpty(part.fileId()))
                .setType(nullToEmpty(part.type()))
                .setMime(nullToEmpty(part.mime()))
                .setSize(part.size())
                .setName(nullToEmpty(part.name()))
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
        if (spec.timeoutSeconds() != null && spec.timeoutSeconds() > 0) {
            builder.setTimeoutSeconds(spec.timeoutSeconds());
        }
        return builder.build();
    }

    public static HistoryMessage toProto(RunHistoryMessage message) {
        HistoryMessage.Builder builder = HistoryMessage.newBuilder()
                .setKind(MessageKindMapper.toProto(message.kind()))
                .setText(nullToEmpty(message.text()));
        if (message.toolTurn() != null) {
            builder.setToolTurn(toProto(message.toolTurn()));
        }
        return builder.build();
    }

    private static ToolTurn toProto(ToolTurnRecord turn) {
        ToolTurn.Builder builder = ToolTurn.newBuilder().setText(nullToEmpty(turn.text()));
        for (ToolTurnRecord.Call call : turn.calls()) {
            builder.addCalls(ToolCallRec.newBuilder()
                    .setId(nullToEmpty(call.id()))
                    .setName(nullToEmpty(call.name()))
                    .setArgumentsJson(nullToEmpty(call.argumentsJson())));
        }
        for (ToolTurnRecord.Result result : turn.results()) {
            builder.addResults(ToolResultRec.newBuilder()
                    .setId(nullToEmpty(result.id()))
                    .setName(nullToEmpty(result.name()))
                    .setOutputJson(nullToEmpty(result.outputJson()))
                    .setFailed(result.failed()));
        }
        return builder.build();
    }
}
