package ru.agimate.userapi.database.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.common.security.UserRole;

import java.util.UUID;

@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uq_users_email", columnNames = "email"),
        @UniqueConstraint(name = "uq_users_referral_code", columnNames = "referral_code")
})
@Getter
@Setter
public class UserEntity extends BaseEntity {
    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, columnDefinition = "TEXT")
    private String email;

    @Column(name = "first_name", columnDefinition = "TEXT")
    private String firstName;

    @Column(name = "last_name", columnDefinition = "TEXT")
    private String lastName;

    @Column(name = "display_name", columnDefinition = "TEXT")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, columnDefinition = "TEXT")
    private UserRole role = UserRole.GUEST;

    /** The code this user hands out; everyone has one, whether or not they ever invite anybody. */
    @Column(name = "referral_code", nullable = false, columnDefinition = "TEXT")
    private String referralCode;

    /**
     * Who invited this user — a snapshot of the moment the account was created. Signing in again
     * through somebody else's link does not change it, so a link can only ever bring new people.
     */
    @Column(name = "referred_by")
    private UUID referredBy;

    // Constructors
    public UserEntity() {}

    public UserEntity(String email, String firstName, String lastName, String displayName) {
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.displayName = displayName;
    }
}
