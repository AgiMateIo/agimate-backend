package ru.agimate.controlapi.database.repositories;

import lombok.experimental.UtilityClass;
import org.springframework.data.jpa.domain.Specification;
import ru.agimate.controlapi.database.entities.Skill;

import java.util.UUID;

@UtilityClass
public class SkillSpecs {

    public static Specification<Skill> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Skill> ownedBy(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    public static Specification<Skill> isPublic() {
        return (root, query, cb) -> cb.isTrue(root.get("isPublic"));
    }

    /**
     * Скилл требует коннектор {@code connectorCode}: containment {@code connector_codes @> ARRAY[code]}.
     * Через {@code @>} (а не {@code array_position}), чтобы задействовать GIN-индекс idx_skills_connector_codes.
     */
    public static Specification<Skill> hasConnector(String connectorCode) {
        return (root, query, cb) -> cb.isTrue(
                cb.function("array_contains", Boolean.class, root.get("connectorCodes"), cb.literal(connectorCode)));
    }

    public static Specification<Skill> searchByNameOrDescription(String search) {
        return (root, query, cb) -> {
            String pattern = "%" + search.toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("name")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern)
            );
        };
    }
}
