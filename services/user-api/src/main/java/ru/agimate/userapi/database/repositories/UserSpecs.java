package ru.agimate.userapi.database.repositories;

import lombok.experimental.UtilityClass;
import org.springframework.data.jpa.domain.Specification;
import ru.agimate.common.security.UserRole;
import ru.agimate.userapi.database.entities.UserEntity;

@UtilityClass
public class UserSpecs {

    public static Specification<UserEntity> hasRole(UserRole role) {
        return (root, query, cb) -> cb.equal(root.get("role"), role);
    }

    /** Case-insensitive substring over the two fields an admin actually searches by. */
    public static Specification<UserEntity> matches(String search) {
        String pattern = "%" + search.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("email")), pattern),
                cb.like(cb.lower(root.get("displayName")), pattern));
    }
}
