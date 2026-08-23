package ru.agimate.userapi.mappers;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NonNull;
import ru.agimate.userapi.controller.dto.response.UserResponse;
import ru.agimate.userapi.database.entities.UserEntity;

@UtilityClass
public class UserMapper {
    public static @NonNull UserResponse getUserResponse(UserEntity userEntity) {
        return new UserResponse(
                userEntity.getId(),
                userEntity.getEmail(),
                userEntity.getFirstName(),
                userEntity.getLastName(),
                userEntity.getDisplayName(),
                userEntity.getRole(),
                userEntity.getReferredBy(),
                userEntity.getPasswordHash() != null,
                userEntity.getCreatedAt(),
                userEntity.getUpdatedAt()
        );
    }
}
