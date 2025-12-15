package ru.agimate.userapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import ru.agimate.userapi.database.entities.User;
import ru.agimate.userapi.database.repositories.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        userService = new UserService(userRepository);
    }

    @Test
    void testFindByEmail() {
        // Given
        String email = "test@example.com";
        User expectedUser = new User();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(expectedUser));

        // When
        Optional<User> result = userService.findByEmail(email);

        // Then
        assertTrue(result.isPresent());
        assertEquals(expectedUser, result.get());
        verify(userRepository).findByEmail(email);
    }

    @Test
    void testCreateUser() {
        // Given
        String email = "test@example.com";
        String firstName = "John";
        String lastName = "Doe";
        String displayName = "John Doe";
        User savedUser = new User(email, firstName, lastName, displayName);
        
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        // When
        User result = userService.createUser(email, firstName, lastName, displayName);

        // Then
        assertEquals(savedUser, result);
        assertEquals(email, result.getEmail());
        assertEquals(firstName, result.getFirstName());
        assertEquals(lastName, result.getLastName());
        assertEquals(displayName, result.getDisplayName());
        
        verify(userRepository).save(any(User.class));
    }
}