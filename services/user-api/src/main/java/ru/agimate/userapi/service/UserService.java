package ru.agimate.userapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.UserRole;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.repositories.UserRepository;
import ru.agimate.userapi.database.repositories.UserSpecs;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;

    public Optional<UserEntity> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<UserEntity> findById(UUID id) {
        return userRepository.findById(id);
    }

    @Transactional
    public UserEntity createUser(String email, String firstName, String lastName, String displayName) {
        UserEntity userEntity = new UserEntity(email, firstName, lastName, displayName);
        return userRepository.save(userEntity);
    }

    @Transactional
    public UserEntity updateUser(UserEntity userEntity) {
        return userRepository.save(userEntity);
    }

    /** Newest first: an admin looks for who has just signed up, not for who signed up first. */
    public Page<UserEntity> listUsers(String search, UserRole role, int page, int size) {
        Specification<UserEntity> spec = Specification.unrestricted();
        if (search != null && !search.isBlank()) {
            spec = spec.and(UserSpecs.matches(search.trim()));
        }
        if (role != null) {
            spec = spec.and(UserSpecs.hasRole(role));
        }
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by("createdAt").descending());
        return userRepository.findAll(spec, pageRequest);
    }

    /**
     * Changing your own role is forbidden — that alone keeps the platform from ever running out of
     * admins: the last one cannot demote themselves, and nobody else is left who could. It also rules
     * out the accidental self-lockout, which is the likelier of the two.
     *
     * @param actorId the admin performing the change; the target may not be the same person
     */
    @Transactional
    public UserEntity changeRole(UUID actorId, UUID targetId, UserRole role) {
        if (actorId.equals(targetId)) {
            throw new BadRequestStatusException("You cannot change your own role");
        }

        UserEntity user = userRepository.findById(targetId)
                .orElseThrow(() -> new NotFoundStatusException("User not found"));
        UserRole previous = user.getRole();
        if (previous == role) {
            return user;
        }

        user.setRole(role);
        UserEntity saved = userRepository.save(user);
        log.info("role changed: user={} {} -> {} by={}", targetId, previous, role, actorId);
        return saved;
    }
}
