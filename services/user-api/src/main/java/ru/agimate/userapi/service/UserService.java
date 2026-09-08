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
import ru.agimate.userapi.config.SignupProperties;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.repositories.UserRepository;
import ru.agimate.userapi.database.repositories.UserSpecs;
import ru.agimate.userapi.util.ReferralCodes;

import java.time.LocalDate;
import java.util.Locale;
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
    private final SignupProperties signupProperties;

    /**
     * Addresses are compared folded, and folded is how they are stored — see {@link #fold}. The whole
     * identity of an account hangs off this one comparison: the provider login joins a second provider
     * to an existing person by it, and the password flows decide by it whether an address is free.
     */
    public Optional<UserEntity> findByEmail(String email) {
        return userRepository.findByEmail(fold(email));
    }

    /**
     * Lower case and trimmed. A mailbox does not care about the case of its name, so neither may the
     * lookup: two accounts for one mailbox is not a duplicate row, it is one person unable to reach
     * half of what is theirs.
     */
    public static String fold(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public Optional<UserEntity> findById(UUID id) {
        return userRepository.findById(id);
    }

    /** Locks the row for the caller's transaction; see {@link UserRepository#findByIdForUpdate}. */
    public Optional<UserEntity> findByIdForUpdate(UUID id) {
        return userRepository.findByIdForUpdate(id);
    }

    public Optional<UserEntity> findByReferralCode(String referralCode) {
        return userRepository.findByReferralCode(referralCode);
    }

    public long countInvited(UUID referrerId) {
        return userRepository.countByReferredBy(referrerId);
    }

    /**
     * The single funnel into {@code users}: both the provider login meeting an unknown address and a
     * registration confirmed by letter end up here, which is what makes the daily quota below a rule
     * of the platform rather than a rule of one entry point.
     *
     * @param referredBy who invited them, or null — set here and never again, so that following
     *                   somebody's link later cannot re-attribute an account that already exists
     */
    @Transactional
    public UserEntity createUser(String email, String firstName, String lastName, String displayName,
                                 UUID referredBy) {
        UserEntity userEntity = new UserEntity(fold(email), firstName, lastName, displayName);
        userEntity.setRole(admissionRole());
        userEntity.setReferralCode(freeReferralCode());
        userEntity.setReferredBy(referredBy);
        return userRepository.save(userEntity);
    }

    /**
     * The first {@code app.signup.daily-admissions} registrations of the day walk in; everybody
     * after them waits for an administrator, the way every account did before the quota existed
     * (docs/decisions/signup-quota.md).
     *
     * <p>Counted are the registrations of the day, not the admissions: a guest of yesterday promoted
     * by hand this morning does not spend a place. The day is the JVM's — {@code created_at} is
     * stamped by Hibernate rather than by the database, so the two ends of this comparison are read
     * off the same clock.
     */
    private UserRole admissionRole() {
        int quota = signupProperties.getDailyAdmissions();
        if (quota <= 0) {
            return UserRole.GUEST;
        }

        userRepository.lockDailyAdmissions();
        long today = userRepository.countByCreatedAtGreaterThanEqual(LocalDate.now().atStartOfDay());
        if (today < quota) {
            return UserRole.USER;
        }

        log.info("daily signup quota {} is spent — the account being created waits for approval", quota);
        return UserRole.GUEST;
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
