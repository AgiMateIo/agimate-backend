package ru.agimate.controlapi.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FileNames")
class FileNamesTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String FILE_ID = "agf_0199c3b2-8f41-8a2c-9d77-1b0e5f2a3c44";

    private static FileLink link(String mime, String name) {
        return new FileLink(USER_ID, FILE_ID, mime, name);
    }

    @Nested
    @DisplayName("forDownload — имя есть")
    class StoredName {

        @Test
        @DisplayName("отдаётся дословно, включая имя без расширения")
        void keptAsIs() {
            assertEquals("Продажи Q3.xlsx", FileNames.forDownload(link("text/csv", "Продажи Q3.xlsx")));
            assertEquals("notes", FileNames.forDownload(link("text/plain", "notes")));
            assertEquals("photo.JPG", FileNames.forDownload(link("image/jpeg", "photo.JPG")));
        }

        @Test
        @DisplayName("пустое имя — как отсутствующее")
        void blankIsNoName() {
            assertTrue(FileNames.forDownload(link("image/png", "  ")).endsWith(".png"));
        }
    }

    @Nested
    @DisplayName("forDownload — имени нет")
    class Synthetic {

        @Test
        @DisplayName("<вид>-<хвост id>.<расширение> вместо agf_<uuid>")
        void kindTailExtension() {
            assertEquals("image-5f2a3c44.png", FileNames.forDownload(link("image/png", null)));
            assertEquals("audio-5f2a3c44.mp3", FileNames.forDownload(link("audio/mpeg", null)));
            assertEquals("video-5f2a3c44.mp4", FileNames.forDownload(link("video/mp4", null)));
        }

        @Test
        @DisplayName("одно и то же имя при каждом вызове")
        void deterministic() {
            assertEquals(FileNames.forDownload(link("image/png", null)),
                    FileNames.forDownload(link("image/png", null)));
        }

        @Test
        @DisplayName("подтип, который не расширение, — из таблицы, иначе bin")
        void extensionTable() {
            assertEquals("file-5f2a3c44.xlsx", FileNames.forDownload(link(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null)));
            assertEquals("file-5f2a3c44.pdf", FileNames.forDownload(link("application/pdf", null)));
            assertEquals("file-5f2a3c44.bin", FileNames.forDownload(link("application/octet-stream", null)));
            assertEquals("file-5f2a3c44.bin", FileNames.forDownload(link("application/x-my-format", null)));
        }

        @Test
        @DisplayName("параметры mime не уезжают в расширение — multipart шлёт charset")
        void mimeParameters() {
            assertEquals("file-5f2a3c44.txt", FileNames.forDownload(link("text/plain;charset=UTF-8", null)));
            assertEquals("image-5f2a3c44.svg", FileNames.forDownload(link("image/svg+xml", null)));
        }

        @Test
        @DisplayName("mime отсутствует или не разбирается — file-….bin")
        void unknownMime() {
            assertEquals("file-5f2a3c44.bin", FileNames.forDownload(link(null, null)));
            assertEquals("file-5f2a3c44.bin", FileNames.forDownload(link("не mime вовсе", null)));
        }
    }

    @Nested
    @DisplayName("kindForMime")
    class Kind {

        @Test
        @DisplayName("image|video|audio|file")
        void byTopLevelType() {
            assertEquals("image", FileNames.kindForMime("image/webp"));
            assertEquals("video", FileNames.kindForMime("video/quicktime"));
            assertEquals("audio", FileNames.kindForMime("audio/ogg"));
            assertEquals("file", FileNames.kindForMime("application/pdf"));
            assertEquals("file", FileNames.kindForMime(null));
        }
    }
}
