package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.integrations.telegram.TelegramToolService;
import ru.agimate.controlapi.connectors.internal.acp.AcpToolService;
import ru.agimate.controlapi.connectors.internal.astro.AstroToolService;
import ru.agimate.controlapi.connectors.internal.board.BoardToolService;
import ru.agimate.controlapi.connectors.internal.divination.DivinationToolService;
import ru.agimate.controlapi.connectors.internal.media.MediaToolService;
import ru.agimate.controlapi.connectors.internal.persistentmemory.PersistentMemoryToolService;
import ru.agimate.controlapi.connectors.internal.platform.PlatformToolService;
import ru.agimate.controlapi.connectors.internal.sheets.SheetsToolService;
import ru.agimate.controlapi.connectors.internal.time.TimeToolService;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Аудит поведенческих подсказок тулов. Обе аннотации пессимистичны по умолчанию
 * ({@code destructiveHint=true}, {@code openWorldHint=true}), поэтому тул, автор которого просто
 * ничего не написал, выглядит и деструктивным, и внешним — и незаметно попадает в оба списка ниже.
 * Незаметно — потому что подсказка ничего не ломает: она лишь советует модели и (в будущем)
 * определяет, что можно бросить при остановке рана.
 *
 * <p>Списки продублированы здесь намеренно, как в {@code ConnectorTextsTest}: тест держится на
 * статике и падает на сборке. Новый тул без осознанного объявления валит именно его — и правка теста
 * и есть то самое решение, которое иначе принялось бы умолчанием.
 */
@DisplayName("ToolAnnotations — аудит подсказок деструктивности и внешнего мира")
class ToolSafetyHintsTest {

    private static final List<Class<?>> TOOL_SERVICES = List.of(
            TelegramToolService.class, AcpToolService.class, AstroToolService.class,
            BoardToolService.class, DivinationToolService.class, MediaToolService.class,
            PersistentMemoryToolService.class, PlatformToolService.class,
            SheetsToolService.class, TimeToolService.class);

    /**
     * Точка невозврата: эффект выходит за пределы платформы, и отменой рана его не вернуть — письмо
     * прочитано, файл на машине пользователя записан, генерация оплачена. Именно этот список, а не
     * деструктивность, решает, что нельзя бросать молча.
     */
    private static final Set<String> POINT_OF_NO_RETURN = Set.of(
            "TelegramToolService.send_message",
            "TelegramToolService.send_photo",
            "TelegramToolService.send_document",
            "TelegramToolService.send_video",
            "TelegramToolService.edit_message",
            "TelegramToolService.delete_message",
            "TelegramToolService.answer_callback_query",
            "AcpToolService.write_file",
            "AcpToolService.run_command",
            "MediaToolService.gen_image",
            "MediaToolService.edit_image",
            "MediaToolService.combine_images");

    /** Перезапись или удаление уже существующего: повтор не «добавит ещё», а затрёт. */
    private static final Set<String> DESTRUCTIVE = Set.of(
            "TelegramToolService.edit_message",
            "TelegramToolService.delete_message",
            "AcpToolService.write_file",
            "AcpToolService.run_command",
            "BoardToolService.edit_task",
            "PersistentMemoryToolService.update_memory",
            "PlatformToolService.update_agent",
            "PlatformToolService.update_skill",
            "PlatformToolService.unbind_skill",
            "SheetsToolService.delete_sheet",
            "SheetsToolService.update_rows",
            "SheetsToolService.delete_rows",
            "TimeToolService.cancel_scheduled");

    @Test
    @DisplayName("наружу уходят ровно объявленные тулы (openWorld и не read-only)")
    void pointOfNoReturnIsDeclared() {
        assertEquals(new TreeSet<>(POINT_OF_NO_RETURN),
                collect(a -> !a.readOnlyHint() && a.openWorldHint()));
    }

    @Test
    @DisplayName("деструктивны ровно объявленные тулы (destructive и не read-only)")
    void destructiveIsDeclared() {
        assertEquals(new TreeSet<>(DESTRUCTIVE),
                collect(a -> !a.readOnlyHint() && a.destructiveHint()));
    }

    private static Set<String> collect(java.util.function.Predicate<ToolAnnotations> matches) {
        Set<String> found = new TreeSet<>();
        for (Class<?> service : TOOL_SERVICES) {
            for (Method method : service.getDeclaredMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                // Джобы и internal-тулы модели не видны: подсказки для них смысла не имеют.
                if (tool == null || tool.internal() || method.isAnnotationPresent(Job.class)) {
                    continue;
                }
                if (matches.test(tool.annotations())) {
                    found.add(service.getSimpleName() + "." + tool.name());
                }
            }
        }
        return found;
    }
}
