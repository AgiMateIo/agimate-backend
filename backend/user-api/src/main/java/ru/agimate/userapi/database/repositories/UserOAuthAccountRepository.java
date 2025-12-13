package ru.agimate.userapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.userapi.database.entities.User;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.entities.OAuthProviderType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserOAuthAccountRepository extends JpaRepository<UserOAuthAccount, Long> {
    Optional<UserOAuthAccount> findByOauthProviderAndOauthUserId(OAuthProviderType provider, String oauthUserId);
    List<UserOAuthAccount> findByUser(User user);
    Optional<UserOAuthAccount> findByUserAndOauthProvider(User user, OAuthProviderType provider);
    Optional<UserOAuthAccount> findByPubId(UUID pubId);
}