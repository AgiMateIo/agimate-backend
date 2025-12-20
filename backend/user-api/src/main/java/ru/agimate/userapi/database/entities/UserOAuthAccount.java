package ru.agimate.userapi.database.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.agimate.common.persistence.BaseEntity;

@Entity
@Table(name = "user_oauth_accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserOAuthAccount extends BaseEntity {

    // Custom constructor for tests
    public UserOAuthAccount(User user, OAuthProviderType oauthProvider, String providerUserId) {
        this.user = user;
        this.oauthProvider = oauthProvider;
        this.providerUserId = providerUserId;
    }
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", nullable = false, columnDefinition = "TEXT")
    private OAuthProviderType oauthProvider;

    @Column(name = "provider_user_id", nullable = false, columnDefinition = "TEXT")
    private String providerUserId;

    @Column(name = "email", nullable = false, unique = true, columnDefinition = "TEXT")
    private String email;

    @Column(name = "first_name", columnDefinition = "TEXT")
    private String firstName;

    @Column(name = "last_name", columnDefinition = "TEXT")
    private String lastName;

}