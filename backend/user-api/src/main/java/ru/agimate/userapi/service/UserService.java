package ru.agimate.userapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.userapi.database.entities.User;
import ru.agimate.userapi.database.repositories.UserRepository;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<User> findByPubId(UUID pubId) {
        return userRepository.findByPubId(pubId);
    }

    @Transactional
    public User createUser(String email, String firstName, String lastName, String displayName) {
        User user = new User(email, firstName, lastName, displayName);
        return userRepository.save(user);
    }

    @Transactional
    public User updateUser(User user) {
        return userRepository.save(user);
    }
}