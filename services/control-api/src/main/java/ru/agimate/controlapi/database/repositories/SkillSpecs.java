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
     * The skill requires the connector {@code connectorCode}: JSONB containment
     * {@code connectors @> '[{"code": ?}]'}, spelled as the operator's function so the criteria API
     * can call it. No index behind it — the table is small and the filter is a listing's.
     */
    public static Specification<Skill> hasConnector(String connectorCode) {
        return (root, query, cb) -> cb.isTrue(cb.function("jsonb_contains", Boolean.class,
                root.get("connectors"),
                cb.function("jsonb_build_array", String.class,
                        cb.function("jsonb_build_object", String.class, cb.literal("code"), cb.literal(connectorCode)))));
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
