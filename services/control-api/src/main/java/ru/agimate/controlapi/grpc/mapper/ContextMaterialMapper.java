package ru.agimate.controlapi.grpc.mapper;

import lombok.experimental.UtilityClass;
import ru.agimate.agentworker.ContextMaterial;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;

/** proto {@code ContextMaterial} ↔ {@link ToolTurnRecord.Material}; {@code NONE} is the domain's {@code null}. */
@UtilityClass
public class ContextMaterialMapper {

    public static ToolTurnRecord.Material toDomain(ContextMaterial material) {
        return switch (material) {
            case CONTEXT_MATERIAL_TOOLS -> ToolTurnRecord.Material.TOOLS;
            case CONTEXT_MATERIAL_SKILL -> ToolTurnRecord.Material.SKILL;
            case CONTEXT_MATERIAL_NONE, UNRECOGNIZED -> null;
        };
    }

    public static ContextMaterial toProto(ToolTurnRecord.Material material) {
        if (material == null) {
            return ContextMaterial.CONTEXT_MATERIAL_NONE;
        }
        return switch (material) {
            case TOOLS -> ContextMaterial.CONTEXT_MATERIAL_TOOLS;
            case SKILL -> ContextMaterial.CONTEXT_MATERIAL_SKILL;
        };
    }
}
