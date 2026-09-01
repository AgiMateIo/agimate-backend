package ru.agimate.common.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PublicTargets — единый гард исходящих адресов")
class PublicTargetsTest {

    private final PublicTargets targets = new PublicTargets(false);
    private final PublicTargets permissive = new PublicTargets(true);

    @Nested
    @DisplayName("классификация адреса")
    class Classification {

        @Test
        @DisplayName("loopback, приватные, link-local и any-local — не публичные")
        void rejectsPrivateRanges() throws UnknownHostException {
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("127.0.0.1")));
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("10.0.0.5")));
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("172.16.0.1")));
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("192.168.1.1")));
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("169.254.169.254")));
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("0.0.0.0")));
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("::1")));
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("fd00::1")));
        }

        @Test
        @DisplayName("CGNAT 100.64.0.0/10 — не публичный: диапазон выглядит маршрутизируемым, но им не является")
        void rejectsCarrierGradeNat() throws UnknownHostException {
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("100.64.0.1")));
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("100.127.255.254")));
            // Границы диапазона: 100.63.x и 100.128.x к нему не относятся.
            assertTrue(PublicTargets.isPublic(InetAddress.getByName("100.63.255.255")));
            assertTrue(PublicTargets.isPublic(InetAddress.getByName("100.128.0.1")));
        }

        @Test
        @DisplayName("IPv4-mapped IPv6 не проходит мимо проверки IPv4-диапазонов")
        void rejectsIpv4MappedIpv6() throws UnknownHostException {
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("::ffff:169.254.169.254")));
            assertFalse(PublicTargets.isPublic(InetAddress.getByName("::ffff:127.0.0.1")));
        }

        @Test
        @DisplayName("публичные адреса проходят")
        void allowsPublic() throws UnknownHostException {
            assertTrue(PublicTargets.isPublic(InetAddress.getByName("8.8.8.8")));
            assertTrue(PublicTargets.isPublic(InetAddress.getByName("2001:4860:4860::8888")));
        }
    }

    @Nested
    @DisplayName("проверка URL")
    class Urls {

        @Test
        @DisplayName("не-http(s) схема и URL без хоста отклоняются")
        void rejectsScheme() {
            assertThrows(TargetNotAllowedException.class, () -> targets.requireAllowed("ftp://example.com/x"));
            assertThrows(TargetNotAllowedException.class, () -> targets.requireAllowed("file:///etc/passwd"));
            assertThrows(TargetNotAllowedException.class, () -> targets.requireAllowed("/relative"));
            assertThrows(TargetNotAllowedException.class, () -> targets.requireAllowed(""));
        }

        @Test
        @DisplayName("httpsOnly отклоняет http: по такому адресу уезжает ключ или токен")
        void rejectsHttpWhenHttpsRequired() {
            assertThrows(TargetNotAllowedException.class,
                    () -> targets.requireAllowed("http://example.com/token", true));
        }

        @Test
        @DisplayName("userinfo отклоняется: user@host разные парсеры читают по-разному")
        void rejectsUserInfo() {
            assertThrows(TargetNotAllowedException.class,
                    () -> targets.requireAllowed("http://evil.com@169.254.169.254/latest/"));
        }

        @Test
        @DisplayName("адрес метаданных облака и loopback блокируются до сетевого вызова")
        void rejectsPrivateTargets() {
            assertThrows(TargetNotAllowedException.class,
                    () -> targets.requireAllowed("http://169.254.169.254/latest/meta-data/"));
            assertThrows(TargetNotAllowedException.class,
                    () -> targets.requireAllowed("http://127.0.0.1:8080/mcp"));
            assertThrows(TargetNotAllowedException.class,
                    () -> targets.requireAllowed("https://10.0.0.5/mcp"));
        }

        @Test
        @DisplayName("allowPrivate снимает проверку адреса, но не проверку схемы")
        void permissiveKeepsSchemeCheck() {
            assertDoesNotThrow(() -> permissive.requireAllowed("http://127.0.0.1:8080/mcp"));
            assertThrows(TargetNotAllowedException.class, () -> permissive.requireAllowed("ftp://127.0.0.1/x"));
        }
    }

    @Nested
    @DisplayName("резолв для DNS-хука")
    class Resolution {

        @Test
        @DisplayName("имя с непубличным адресом отвергается целиком")
        void rejectsNameResolvingToPrivate() {
            // localhost резолвится через hosts в 127.0.0.1 и/или ::1 — годиться не должен ни один.
            assertThrows(TargetNotAllowedException.class, () -> targets.resolve("localhost"));
        }

        @Test
        @DisplayName("несуществующее имя — UnknownHostException, а не отказ гарда")
        void unresolvableIsNotARefusal() {
            assertThrows(UnknownHostException.class,
                    () -> targets.resolve("no-such-host.invalid"));
        }
    }
}
