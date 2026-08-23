package ru.agimate.userapi.database.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.util.UUID;

@Entity
@Table(name = "user_oauth_accounts",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_user_oauth_accounts_oauth_provider_provider_user_id",
               columnNames = {"oauth_provider", "provider_user_id"}
       ))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserOAuthAccount extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity userEntity;

    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", nullable = false, columnDefinition = "TEXT")
    private OAuthProviderType oauthProvider;

    @Column(name = "provider_user_id", nullable = false, columnDefinition = "TEXT")
    private String providerUserId;

    /**
     * Not unique: the same person may arrive through several providers with the same mailbox. Null
     * where a provider was linked deliberately and reports no address — identity there is the account
     * that was already signed in, so nothing depends on this value.
     */
    @Column(name = "email", columnDefinition = "TEXT")
    private String email;

    @Column(name = "first_name", columnDefinition = "TEXT")
    private String firstName;

    @Column(name = "last_name", columnDefinition = "TEXT")
    private String lastName;

}
