package ru.agimate.controlapi.connectors.integrations.ssh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SshOutputCollector")
class SshOutputCollectorTest {

    @Nested
    @DisplayName("предел вывода")
    class ByteLimit {

        @Test
        @DisplayName("ровно 32 КиБ не считается усечением")
        void exactLimitIsNotTruncated() {
            SshOutputCollector collector = new SshOutputCollector();

            collector.write(new byte[SshOutputCollector.BYTE_LIMIT], 0, SshOutputCollector.BYTE_LIMIT);

            assertFalse(collector.truncated());
            assertEquals(SshOutputCollector.BYTE_LIMIT, collector.bytes().length);
        }

        @Test
        @DisplayName("превышение в одной записи включает флаг и оставляет первые 32 КиБ")
        void singleWriteOverLimitIsTruncated() throws IOException {
            SshOutputCollector collector = new SshOutputCollector();
            byte[] chunk = new byte[SshOutputCollector.BYTE_LIMIT + 100];
            Arrays.fill(chunk, (byte) 'x');

            collector.write(chunk);

            assertTrue(collector.truncated());
            assertEquals(SshOutputCollector.BYTE_LIMIT, collector.bytes().length);
        }

        @Test
        @DisplayName("превышение на границе нескольких записей включает флаг")
        void consecutiveWritesOverLimitAreTruncated() {
            SshOutputCollector collector = new SshOutputCollector();
            collector.write(new byte[SshOutputCollector.BYTE_LIMIT], 0, SshOutputCollector.BYTE_LIMIT);

            collector.write(new byte[1], 0, 1);

            assertTrue(collector.truncated());
            assertEquals(SshOutputCollector.BYTE_LIMIT, collector.bytes().length);
        }

        @Test
        @DisplayName("сохраняет начало превышающей записи, остальное принимает и отбрасывает")
        void keepsOnlyThePrefix() {
            SshOutputCollector collector = new SshOutputCollector();
            collector.write(new byte[SshOutputCollector.BYTE_LIMIT - 2], 0, SshOutputCollector.BYTE_LIMIT - 2);

            collector.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 0, 10);

            byte[] bytes = collector.bytes();
            assertEquals(SshOutputCollector.BYTE_LIMIT, bytes.length);
            assertArrayEquals(new byte[]{1, 2}, Arrays.copyOfRange(bytes, bytes.length - 2, bytes.length));
            assertTrue(collector.truncated());
        }

        @Test
        @DisplayName("посимвольная запись тоже подчиняется пределу")
        void singleByteWritesRespectLimit() {
            SshOutputCollector collector = new SshOutputCollector();
            for (int i = 0; i < SshOutputCollector.BYTE_LIMIT; i++) {
                collector.write('a');
            }
            assertFalse(collector.truncated());

            collector.write('b');

            assertTrue(collector.truncated());
            assertEquals(SshOutputCollector.BYTE_LIMIT, collector.bytes().length);
        }

        @Test
        @DisplayName("bytes() возвращает копию, а не внутренний буфер")
        void bytesReturnsACopy() {
            SshOutputCollector collector = new SshOutputCollector();
            collector.write(new byte[]{1, 2, 3}, 0, 3);

            byte[] first = collector.bytes();
            first[0] = 99;

            assertArrayEquals(new byte[]{1, 2, 3}, collector.bytes());
        }
    }

    @Nested
    @DisplayName("декодирование UTF-8")
    class Utf8Decoding {

        @Test
        @DisplayName("кириллица декодируется как UTF-8")
        void decodesUtf8() {
            SshOutputCollector collector = new SshOutputCollector();
            byte[] text = "Привет, SSH".getBytes(StandardCharsets.UTF_8);

            collector.write(text, 0, text.length);

            assertEquals("Привет, SSH", collector.text());
        }

        @Test
        @DisplayName("символ, разрезанный границей предела, превращается в U+FFFD")
        void splitCharacterBecomesReplacement() {
            SshOutputCollector collector = new SshOutputCollector();
            collector.write(new byte[SshOutputCollector.BYTE_LIMIT - 1], 0, SshOutputCollector.BYTE_LIMIT - 1);
            byte[] symbol = "ж".getBytes(StandardCharsets.UTF_8);
            assertEquals(2, symbol.length);

            collector.write(symbol, 0, symbol.length);

            String text = collector.text();
            assertTrue(collector.truncated());
            assertEquals(SshOutputCollector.BYTE_LIMIT - 1, text.length() - 1);
            assertEquals('�', text.charAt(text.length() - 1));
        }

        @Test
        @DisplayName("некорректная последовательность заменяется, а не бросает исключение")
        void malformedSequenceBecomesReplacement() {
            SshOutputCollector collector = new SshOutputCollector();
            byte[] malformed = {(byte) 0xFF, (byte) 0xFE};

            collector.write(malformed, 0, malformed.length);

            assertEquals("��", collector.text());
            assertFalse(collector.truncated());
        }
    }

    @Nested
    @DisplayName("независимость потоков")
    class IndependentStreams {

        @Test
        @DisplayName("stdout и stderr собираются независимо")
        void stdoutAndStderrAreIndependent() {
            SshOutputCollector stdout = new SshOutputCollector();
            SshOutputCollector stderr = new SshOutputCollector();
            byte[] out = "out".getBytes(StandardCharsets.UTF_8);
            byte[] err = "err".getBytes(StandardCharsets.UTF_8);

            stdout.write(out, 0, out.length);
            stderr.write(err, 0, err.length);

            assertEquals("out", stdout.text());
            assertEquals("err", stderr.text());
        }

        @Test
        @DisplayName("переполнение одного потока не усекает другой")
        void oneStreamTruncatingDoesNotAffectTheOther() {
            SshOutputCollector stdout = new SshOutputCollector();
            SshOutputCollector stderr = new SshOutputCollector();
            stdout.write(new byte[SshOutputCollector.BYTE_LIMIT + 1], 0, SshOutputCollector.BYTE_LIMIT + 1);
            byte[] err = "short".getBytes(StandardCharsets.UTF_8);

            stderr.write(err, 0, err.length);

            assertTrue(stdout.truncated());
            assertFalse(stderr.truncated());
            assertEquals("short", stderr.text());
        }
    }
}
