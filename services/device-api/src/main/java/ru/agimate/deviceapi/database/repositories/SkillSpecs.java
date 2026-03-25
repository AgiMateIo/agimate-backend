package ru.agimate.deviceapi.database.repositories;

import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.experimental.UtilityClass;
import org.springframework.data.jpa.domain.Specification;
import ru.agimate.deviceapi.database.entities.Skill;
import ru.agimate.deviceapi.database.entities.SkillConnector;

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

    public static Specification<Skill> hasConnector(String connectorCode) {
        return (root, query, cb) -> {
            Subquery<Long> subquery = query.subquery(Long.class);
            Root<SkillConnector> sc = subquery.from(SkillConnector.class);
            subquery.select(cb.literal(1L));
            subquery.where(
                    cb.equal(sc.get("skill").get("id"), root.get("id")),
                    cb.equal(sc.get("connectorCode"), connectorCode)
            );
            return cb.exists(subquery);
        };
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
