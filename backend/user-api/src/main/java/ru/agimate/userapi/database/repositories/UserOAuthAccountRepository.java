package ru.agimate.userapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.userapi.database.entities.User;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.entities.OAuthProviderType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserOAuthAccountRepository extends JpaRepository<UserOAuthAccount, Long> {
    Optional<UserOAuthAccount> findByOauthProviderAndProviderUserId(OAuthProviderType provider, String providerUserId);
    List<UserOAuthAccount> findByUser(User user);
    Optional<UserOAuthAccount> findByUserAndOauthProvider(User user, OAuthProviderType provider);

    // Custom query to fetch UserOAuthAccount with its associated User
    @Query("SELECT uoa FROM UserOAuthAccount uoa JOIN FETCH uoa.user WHERE uoa.oauthProvider = :provider AND uoa.providerUserId = :providerUserId")
    Optional<UserOAuthAccount> findByOauthProviderAndProviderUserIdWithUser(@Param("provider") OAuthProviderType provider, @Param("providerUserId") String providerUserId);
}