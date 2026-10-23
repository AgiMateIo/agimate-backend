package ru.agimate.controlapi.database.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cases of the matching rule. The same table is the check for {@code skill_fits_connection} in
 * SQL — keep the two in step (docs/decisions/skills-for-connection.md).
 */
@DisplayName("ConnectorRequirement.fits")
class ConnectorRequirementTest {

    private static final String URL = "https://x/mcp";

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("без params — любой экземпляр кода", "mcp", null, "mcp", Map.of("url", URL), true),
                Arguments.of("без params — и с пустой открытой частью", "mcp", null, "mcp", Map.of(), true),
                Arguments.of("другой код не подходит", "mcp", null, "telegram", Map.of(), false),
                Arguments.of("тот же url", "mcp", Map.of("url", URL), "mcp", Map.of("url", URL), true),
                Arguments.of("другой url", "mcp", Map.of("url", URL), "mcp", Map.of("url", "https://y/mcp"), false),
                Arguments.of("у подключения полей больше — подходит", "mcp", Map.of("url", URL), "mcp",
                        Map.of("url", URL, "region", "eu"), true),
                Arguments.of("пустая открытая часть не содержит url", "mcp", Map.of("url", URL), "mcp", Map.of(), false),
                Arguments.of("пустые params требования — любой экземпляр", "mcp", Map.of(), "mcp", Map.of("url", URL), true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void fits(String name, String code, Map<String, String> params, String connectorCode,
              Map<String, String> connectionParams, boolean expected) {
        ConnectorRequirement requirement = new ConnectorRequirement(code, code, null, params, null, null);
        assertEquals(expected, requirement.fits(connectorCode, connectionParams));
    }
}
