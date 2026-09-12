package ru.agimate.controlapi.database.repositories;

import lombok.experimental.UtilityClass;
import org.springframework.data.jpa.domain.Specification;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.ContentCategory;

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

    public static Specification<Skill> hasCategory(ContentCategory category) {
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    /**
     * The skill carries the tag: {@code array_position(tags, ?) > 0}. Not containment — there is no
     * {@code text[] @> varchar[]} operator, and an operator is not callable from the criteria API — and
     * not a null check on the position either: Hibernate renders an array function wrapped in
     * {@code coalesce(..., 0)}, so {@code IS NOT NULL} is true for every row and the filter silently
     * lets everything through. No index — as with {@link #hasConnector(String)}, the table is small.
     */
    public static Specification<Skill> hasTag(String tag) {
        return (root, query, cb) -> cb.greaterThan(
                cb.function("array_position", Integer.class, root.get("tags"), cb.literal(tag)), 0);
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
