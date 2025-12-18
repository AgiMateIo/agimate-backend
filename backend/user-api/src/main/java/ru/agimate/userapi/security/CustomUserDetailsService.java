package ru.agimate.userapi.security;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.userapi.database.entities.User;
import ru.agimate.userapi.database.repositories.UserRepository;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public @NonNull UserDetails loadUserByUsername(@NonNull String pubId) throws UsernameNotFoundException {
        return getByPubId(pubId);
    }

    @Transactional
    public UserDetails getByPubId(String id) {
        User user = userRepository.findByPubId(UUID.fromString(id))
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + id));

        return UserPrincipal.create(user);
    }

    @Transactional
    public Optional<UserDetails> findByPubId(String id) {
        return userRepository.findByPubId(UUID.fromString(id)).map(UserPrincipal::create);
    }
}