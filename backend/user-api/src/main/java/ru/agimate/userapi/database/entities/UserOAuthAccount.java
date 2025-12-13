package ru.agimate.userapi.database.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import ru.agimate.common.util.UUIDUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_oauth_accounts")
@Getter
@Setter
public class UserOAuthAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    
    @Column(name = "pub_id", unique = true, nullable = false)
    private UUID pubId = UUIDUtils.generateUUIDv8();
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", nullable = false, columnDefinition = "TEXT")
    private OAuthProviderType oauthProvider;
    
    @Column(name = "oauth_user_id", nullable = false, columnDefinition = "TEXT")
    private String oauthUserId;
    
    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;
    
    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;
    
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    
    @Column(name = "scopes", columnDefinition = "TEXT")
    private String scopes;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    // Constructors
    public UserOAuthAccount() {}
    
    public UserOAuthAccount(User user, OAuthProviderType oauthProvider, String oauthUserId) {
        this.user = user;
        this.oauthProvider = oauthProvider;
        this.oauthUserId = oauthUserId;
    }
}