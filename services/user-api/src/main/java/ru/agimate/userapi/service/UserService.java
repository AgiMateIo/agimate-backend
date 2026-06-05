package ru.agimate.userapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.repositories.UserRepository;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public Optional<UserEntity> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<UserEntity> findById(UUID id) {
        return userRepository.findById(id);
    }

    @Transactional
    public UserEntity createUser(String email, String firstName, String lastName, String displayName) {
        UserEntity userEntity = new UserEntity(email, firstName, lastName, displayName);
        return userRepository.save(userEntity);
    }

    @Transactional
    public UserEntity updateUser(UserEntity userEntity) {
        return userRepository.save(userEntity);
    }
}