package ru.agimate.deviceapi.database.repositories;

import lombok.experimental.UtilityClass;
import org.springframework.data.jpa.domain.Specification;
import ru.agimate.deviceapi.database.entities.Skill;

import java.util.UUID;

@UtilityClass
public class SkillSpecs {

    public static Specification<Skill> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Skill> ownedBy(UUID userPubId) {
        return (root, query, cb) -> cb.equal(root.get("userPubId"), userPubId);
    }

    public static Specification<Skill> publicNotFeatured() {
        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("isPublic")),
                cb.isFalse(root.get("isFeatured"))
        );
    }

    public static Specification<Skill> featured() {
        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("isPublic")),
                cb.isTrue(root.get("isFeatured"))
        );
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
