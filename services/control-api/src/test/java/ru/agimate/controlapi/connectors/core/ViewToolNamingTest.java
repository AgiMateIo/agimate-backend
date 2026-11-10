package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.integrations.telegram.TelegramToolService;
import ru.agimate.controlapi.connectors.internal.acp.AcpToolService;
import ru.agimate.controlapi.connectors.internal.astro.AstroToolService;
import ru.agimate.controlapi.connectors.internal.board.BoardToolService;
import ru.agimate.controlapi.connectors.internal.divination.DivinationToolService;
import ru.agimate.controlapi.connectors.internal.media.MediaToolService;
import ru.agimate.controlapi.connectors.internal.persistentmemory.PersistentMemoryToolService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformAgentToolService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformConnectionToolService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformLlmToolService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformObservabilityToolService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformWorkspaceToolService;
import ru.agimate.controlapi.connectors.internal.sheets.SheetsToolService;
import ru.agimate.controlapi.connectors.internal.time.TimeToolService;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Вью открывает только тул показа: раз вью — ресурс, а не результат чтения, вход в неё один и
 * явный, и это видно по имени. Правило двустороннее — {@code show_*} обещает вью, вью обещает
 * {@code show_*}. Без него {@code view} снова расползётся по читающим тулам, и каждый вызов в
 * одном ходе нарисует свою панель (так и случилось с {@code get_memory} + {@code get_memory_notes}).
 *
 * <p>Список сервисов продублирован намеренно, как в {@link ToolSafetyHintsTest}: новый tool-сервис
 * добавляется и сюда, а осознанное исключение — это правка теста, а не умолчание.
 */
@DisplayName("Вью-тулы называются show_*, и show_* всегда несёт вью")
class ViewToolNamingTest {

    private static final String SHOW_PREFIX = "show_";

    private static final List<Class<?>> TOOL_SERVICES = List.of(
            TelegramToolService.class, AcpToolService.class, AstroToolService.class,
            BoardToolService.class, DivinationToolService.class, MediaToolService.class,
            PersistentMemoryToolService.class, PlatformAgentToolService.class, PlatformConnectionToolService.class,
            PlatformLlmToolService.class, PlatformWorkspaceToolService.class, PlatformObservabilityToolService.class,
            SheetsToolService.class, TimeToolService.class);

    @Test
    @DisplayName("тул с вью начинается с show_, и show_* всегда объявляет вью")
    void viewToolsAreShowTools() {
        for (Class<?> service : TOOL_SERVICES) {
            for (Method method : service.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null || method.isAnnotationPresent(Job.class)) {
                    continue;
                }
                String name = service.getSimpleName() + "." + tool.name();
                boolean hasView = !tool.view().isBlank();
                if (hasView) {
                    assertTrue(tool.name().startsWith(SHOW_PREFIX),
                            name + ": вью вешается на тул показа, имя должно начинаться с " + SHOW_PREFIX);
                }
                if (tool.name().startsWith(SHOW_PREFIX)) {
                    assertTrue(hasView, name + ": имя " + SHOW_PREFIX + "* обещает вью, а view пуст");
                }
            }
        }
    }
}
