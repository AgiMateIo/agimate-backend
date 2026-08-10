package ru.agimate.controlapi.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полнота списка путей user-JWT цепочки. Цепочка матчится перечислением, а не префиксом
 * {@code /manage/**}, и забытый контроллер попадает в цепочки ниже — где пользовательского токена
 * никто не разбирает. Отказ получается fail-closed, но выглядит как «доступ запрещён» у законного
 * владельца, и найти причину по симптому почти невозможно: эндпойнт есть, роль есть, токен есть.
 *
 * <p>Проверка ровно та, что в чек-листе CLAUDE.md («security filter chain includes new paths») —
 * поэтому пусть её делает сборка, а не память.
 */
@DisplayName("SecurityConfig — /manage-пути в JWT-цепочке")
class ManagePathsSecurityTest {

    private static final String MANAGE_PACKAGE = "ru.agimate.controlapi.controller.manage";

    @Test
    @DisplayName("каждый @RestController из manage накрыт матчером цепочки")
    void everyManageControllerIsCovered() {
        List<String> uncovered = new ArrayList<>();
        for (Class<?> controller : manageControllers()) {
            String path = pathConstant(controller);
            if (path != null && !covered(path)) {
                uncovered.add(controller.getSimpleName() + " (" + path + ")");
            }
        }

        assertTrue(uncovered.isEmpty(),
                "не попали в SecurityConfig.JWT_CHAIN_PATHS: " + uncovered);
    }

    /** Матчер вида {@code /manage/x/**} накрывает и сам путь, и всё под ним. */
    private static boolean covered(String path) {
        return Arrays.stream(SecurityConfig.JWT_CHAIN_PATHS)
                .map(pattern -> pattern.endsWith("/**")
                        ? pattern.substring(0, pattern.length() - "/**".length())
                        : pattern)
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    private static Set<Class<?>> manageControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<Class<?>> classes = new java.util.LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(MANAGE_PACKAGE)) {
            try {
                classes.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("scanned class is not loadable: " + definition, e);
            }
        }
        assertTrue(classes.size() > 10, "сканер не нашёл контроллеры — проверь пакет " + MANAGE_PACKAGE);
        return classes;
    }

    /** Соглашение раздела: у контроллера есть {@code public static final String PATH}. */
    private static String pathConstant(Class<?> controller) {
        try {
            Field field = controller.getDeclaredField("PATH");
            return (String) field.get(null);
        } catch (NoSuchFieldException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("PATH is not readable on " + controller.getName(), e);
        }
    }
}
