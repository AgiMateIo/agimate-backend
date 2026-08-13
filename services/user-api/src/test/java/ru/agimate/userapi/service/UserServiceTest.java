package ru.agimate.userapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.UserRole;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.repositories.UserRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — заведение пользователя, админский листинг и смена роли")
class UserServiceTest {

    private static final UUID ACTOR_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService service;

    private static UserEntity user(UUID id, UserRole role) {
        UserEntity entity = new UserEntity("target@example.com", "Target", "User", "target");
        entity.setId(id);
        entity.setRole(role);
        return entity;
    }

    @Nested
    @DisplayName("createUser")
    class CreateUser {

        @Test
        @DisplayName("занятый код отбрасывается — пользователь получает следующий свободный")
        void retriesUntilReferralCodeIsFree() {
            when(userRepository.existsByReferralCode(any())).thenReturn(true, false);
            when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            UserEntity created = service.createUser("new@example.com", "New", "User", "new", null);

            assertNotNull(created.getReferralCode());
            verify(userRepository, times(2)).existsByReferralCode(any());
        }

        @Test
        @DisplayName("пригласивший сохраняется тем, кем пришёл")
        void keepsReferrer() {
            UUID referrer = UUID.randomUUID();
            when(userRepository.existsByReferralCode(any())).thenReturn(false);
            when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            UserEntity created = service.createUser("new@example.com", "New", "User", "new", referrer);

            assertEquals(referrer, created.getReferredBy());
        }
    }

    @Nested
    @DisplayName("listUsers")
    class ListUsers {

        @Test
        @DisplayName("сортировка от новых к старым, размер страницы ограничен сверху")
        void sortsNewestFirstAndCapsPageSize() {
            when(userRepository.findAll(ArgumentMatchers.<Specification<UserEntity>>any(),
                    any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));

            service.listUsers(null, null, 2, 1_000);

            ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
            verify(userRepository).findAll(ArgumentMatchers.<Specification<UserEntity>>any(), captor.capture());
            PageRequest pageRequest = captor.getValue();
            assertEquals(2, pageRequest.getPageNumber());
            assertEquals(100, pageRequest.getPageSize());
            assertEquals(Sort.by("createdAt").descending(), pageRequest.getSort());
        }
    }

    @Nested
    @DisplayName("changeRole")
    class ChangeRole {

        @Test
        @DisplayName("роль меняется, прежняя возвращается в сущности")
        void changesRole() {
            UserEntity target = user(TARGET_ID, UserRole.GUEST);
            when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
            when(userRepository.save(target)).thenReturn(target);

            UserEntity saved = service.changeRole(ACTOR_ID, TARGET_ID, UserRole.USER);

            assertEquals(UserRole.USER, saved.getRole());
            verify(userRepository).save(target);
        }

        @Test
        @DisplayName("своя роль неприкосновенна — иначе последний админ разлогинит платформу")
        void rejectsSelfChange() {
            assertThrows(BadRequestStatusException.class,
                    () -> service.changeRole(ACTOR_ID, ACTOR_ID, UserRole.USER));

            verify(userRepository, never()).findById(any());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("неизвестный пользователь — 404")
        void rejectsUnknownUser() {
            when(userRepository.findById(TARGET_ID)).thenReturn(Optional.empty());

            assertThrows(NotFoundStatusException.class,
                    () -> service.changeRole(ACTOR_ID, TARGET_ID, UserRole.ADMIN));
        }

        @Test
        @DisplayName("роль та же — записи нет")
        void skipsSaveWhenRoleUnchanged() {
            UserEntity target = user(TARGET_ID, UserRole.USER);
            when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

            service.changeRole(ACTOR_ID, TARGET_ID, UserRole.USER);

            verify(userRepository, never()).save(any());
        }
    }
}
