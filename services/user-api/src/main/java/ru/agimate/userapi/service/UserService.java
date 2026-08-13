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
import ru.agimate.userapi.util.ReferralCodes;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int REFERRAL_CODE_ATTEMPTS = 3;

    private final UserRepository userRepository;

    public Optional<UserEntity> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<UserEntity> findById(UUID id) {
        return userRepository.findById(id);
    }

    public Optional<UserEntity> findByReferralCode(String referralCode) {
        return userRepository.findByReferralCode(referralCode);
    }

    public long countInvited(UUID referrerId) {
        return userRepository.countByReferredBy(referrerId);
    }

    /**
     * @param referredBy who invited them, or null — set here and never again, so that following
     *                   somebody's link later cannot re-attribute an account that already exists
     */
    @Transactional
    public UserEntity createUser(String email, String firstName, String lastName, String displayName,
                                 UUID referredBy) {
        UserEntity userEntity = new UserEntity(email, firstName, lastName, displayName);
        userEntity.setReferralCode(freeReferralCode());
        userEntity.setReferredBy(referredBy);
        return userRepository.save(userEntity);
    }

    /**
     * Taken is checked before the insert instead of catching the constraint violation after it: a
     * failed statement marks the PostgreSQL transaction as aborted, and retrying inside it is no
     * longer possible. Two concurrent signups drawing the same code out of 2^40 remain possible in
     * theory and would surface as a failed registration.
     */
    private String freeReferralCode() {
        for (int attempt = 0; attempt < REFERRAL_CODE_ATTEMPTS; attempt++) {
            String code = ReferralCodes.generate();
            if (!userRepository.existsByReferralCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException(
                "No free referral code in " + REFERRAL_CODE_ATTEMPTS + " attempts");
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
