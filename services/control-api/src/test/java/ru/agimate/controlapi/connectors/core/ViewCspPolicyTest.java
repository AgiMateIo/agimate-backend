package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.dto.ViewCsp;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ViewCspPolicy — allow-list внешних доменов вью")
class ViewCspPolicyTest {

    private final ViewCspPolicy policy = new ViewCspPolicy();

    @Nested
    @DisplayName("строгая политика")
    class Strict {

        @Test
        @DisplayName("пустой allow-list не выдаёт ничего")
        void emptyAllowListGrantsNothing() {
            assertNull(policy.grant(new ViewCsp(List.of("https://esm.sh"), List.of("https://esm.sh"))));
        }

        @Test
        @DisplayName("null и пустой ViewCsp дают null")
        void nothingDeclared() {
            assertNull(policy.grant(null));
            assertNull(policy.grant(new ViewCsp(null, null)));
            assertNull(policy.grant(new ViewCsp(List.of(), List.of())));
        }
    }

    @Nested
    @DisplayName("точный origin")
    class ExactOrigin {

        @Test
        @DisplayName("домен из allow-list выдаётся")
        void grantsListedDomain() {
            policy.setAllowedResourceDomains(List.of("https://esm.sh"));

            assertEquals(new ViewCsp(List.of("https://esm.sh"), null),
                    policy.grant(new ViewCsp(List.of("https://esm.sh"), null)));
        }

        @Test
        @DisplayName("схема и хост приводятся к нижнему регистру, слэш в allow-list не мешает")
        void normalizesSchemeHostAndSlash() {
            policy.setAllowedResourceDomains(List.of("HTTPS://ESM.SH/"));

            assertEquals(new ViewCsp(List.of("https://esm.sh"), null),
                    policy.grant(new ViewCsp(List.of("https://ESM.SH"), null)));
        }

        @Test
        @DisplayName("http, wildcard, путь, query, fragment и userinfo отбрасываются")
        void rejectsNonOriginValues() {
            policy.setAllowedResourceDomains(List.of("https://esm.sh"));

            assertNull(policy.grant(new ViewCsp(List.of(
                    "http://esm.sh",
                    "https://*.esm.sh",
                    "https://esm.sh/path",
                    "https://esm.sh?query",
                    "https://esm.sh#fragment",
                    "https://user@esm.sh",
                    "https://evil.example"), null)));
        }

        @Test
        @DisplayName("негодные объявленные значения пропускаются, годные выдаются")
        void skipsMalformedDeclaredValues() {
            policy.setAllowedResourceDomains(List.of("https://esm.sh"));

            assertEquals(new ViewCsp(List.of("https://esm.sh"), null),
                    policy.grant(new ViewCsp(
                            List.of("http://esm.sh", "https://esm.sh/path", "https://esm.sh"), null)));
        }

        @Test
        @DisplayName("порт должен совпадать")
        void portMustMatch() {
            policy.setAllowedResourceDomains(List.of("https://esm.sh:8443"));

            assertEquals(new ViewCsp(List.of("https://esm.sh:8443"), null),
                    policy.grant(new ViewCsp(List.of("https://esm.sh:8443"), null)));
            assertNull(policy.grant(new ViewCsp(List.of("https://esm.sh"), null)));
        }

        @Test
        @DisplayName("дубликаты после нормализации схлопываются")
        void deduplicatesNormalizedOrigins() {
            policy.setAllowedResourceDomains(List.of("https://esm.sh"));

            assertEquals(new ViewCsp(List.of("https://esm.sh"), null),
                    policy.grant(new ViewCsp(List.of("https://esm.sh", "HTTPS://ESM.SH"), null)));
        }
    }

    @Nested
    @DisplayName("категории")
    class Categories {

        @Test
        @DisplayName("resource и connect разрешаются независимо")
        void categoriesAreIndependent() {
            policy.setAllowedResourceDomains(List.of("https://cdn.example"));
            policy.setAllowedConnectDomains(List.of("https://api.example"));

            ViewCsp granted = policy.grant(new ViewCsp(
                    List.of("https://cdn.example", "https://api.example"),
                    List.of("https://api.example", "https://cdn.example")));

            assertEquals(new ViewCsp(List.of("https://cdn.example"), List.of("https://api.example")), granted);
        }

        @Test
        @DisplayName("категория без allow-list не выдаёт домены")
        void categoryWithoutAllowListGrantsNothing() {
            policy.setAllowedResourceDomains(List.of("https://cdn.example"));

            assertEquals(new ViewCsp(List.of("https://cdn.example"), null),
                    policy.grant(new ViewCsp(
                            List.of("https://cdn.example"), List.of("https://cdn.example"))));
        }
    }

    @Nested
    @DisplayName("записи allow-list")
    class AllowListEntries {

        @Test
        @DisplayName("не-https и wildcard в allow-list игнорируются")
        void malformedEntriesGrantNothing() {
            policy.setAllowedResourceDomains(List.of("http://esm.sh", "https://*.esm.sh", "esm.sh"));

            assertNull(policy.grant(new ViewCsp(
                    List.of("http://esm.sh", "https://esm.sh", "esm.sh"), null)));
        }
    }
}
