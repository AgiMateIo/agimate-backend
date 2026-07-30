package ru.agimate.controlapi.controller.manage.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * В админском разделе гейт — это путь: {@code ROLE_ADMIN} требует security-цепочка на префиксе
 * {@link ManageAdminPaths#PREFIX}, а {@code @PreAuthorize} в контроллерах раздела намеренно нет.
 * Значит контроллер, повешенный на путь вне префикса, открыт всем ролям manage-цепочки и никак
 * себя не выдаёт — ни ошибкой сборки, ни падением контекста. Это единственная проверка на то.
 */
@DisplayName("Раздел /manage/admin — путь как гейт")
class ManageAdminSectionTest {

    static Stream<Class<?>> adminControllers() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(ManageAdminPaths.class.getPackageName()).stream()
                .map(bean -> {
                    try {
                        return Class.forName(bean.getBeanClassName());
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    @Test
    @DisplayName("сканирование находит контроллеры раздела — иначе проверка проходит вхолостую")
    void sectionIsNotEmpty() {
        assertFalse(adminControllers().toList().isEmpty(),
                "no @RestController found in " + ManageAdminPaths.class.getPackageName());
    }

    @ParameterizedTest
    @MethodSource("adminControllers")
    @DisplayName("каждый контроллер раздела смонтирован под префиксом")
    void mountedUnderPrefix(Class<?> controller) {
        RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, controller.getSimpleName() + " has no @RequestMapping");

        List<String> paths = Stream.concat(Stream.of(mapping.value()), Stream.of(mapping.path()))
                .distinct()
                .toList();
        assertFalse(paths.isEmpty(), controller.getSimpleName() + " declares no path");
        paths.forEach(path -> assertTrue(path.startsWith(ManageAdminPaths.PREFIX),
                controller.getSimpleName() + " is mounted at " + path
                        + ", outside " + ManageAdminPaths.PREFIX + " — the ADMIN gate would not apply"));
    }
}
