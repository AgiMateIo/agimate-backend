package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("FullCodes")
class FullCodesTest {

    private static final String MCP = "mcp";
    private static final String URL = "https://mcp.notion.com/mcp";

    @Nested
    @DisplayName("слаг от идентификатора")
    class FromIdentifier {

        @Test
        @DisplayName("URL сводится к главной метке хоста")
        void mainHostLabel() {
            assertEquals("mcp_notion", FullCodes.fullCode(MCP, URL));
            assertEquals("mcp_context7", FullCodes.fullCode(MCP, "https://mcp.context7.com/mcp"));
        }

        @Test
        @DisplayName("не-URL идентификатор слагифицируется как есть")
        void plainIdentifier() {
            assertEquals("telegram_my_bot", FullCodes.fullCode("telegram", "My Bot"));
        }

        @Test
        @DisplayName("пустой идентификатор — код коннектора вместо слага")
        void blankIdentifier() {
            assertEquals("mcp_mcp", FullCodes.fullCode(MCP, ""));
        }
    }

    @Nested
    @DisplayName("слаг от имени коннекции")
    class FromName {

        @Test
        @DisplayName("имя становится различителем: два аккаунта на одном URL различимы")
        void nameWins() {
            assertEquals("notion_rabota", FullCodes.nameSlug(MCP, URL, "Notion работа"));
            assertEquals("notion_lichnoe", FullCodes.nameSlug(MCP, URL, "Notion личное"));
        }

        @Test
        @DisplayName("кириллица транслитерируется, а не вычищается в пустоту")
        void cyrillic() {
            assertEquals("rabochii_notion", FullCodes.nameSlug(MCP, URL, "Рабочий Notion"));
        }

        @Test
        @DisplayName("имени нет или оно нечитаемо — падаем обратно на идентификатор")
        void fallsBackToIdentifier() {
            assertEquals("notion", FullCodes.nameSlug(MCP, URL, null));
            assertEquals("notion", FullCodes.nameSlug(MCP, URL, "   "));
            assertEquals("notion", FullCodes.nameSlug(MCP, URL, "!!!"));
        }
    }
}
