package ru.agimate.controlapi.service.llm.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.service.llm.media.MediaTransport.InputImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("JpegRecompressor — сгенерированный PNG в JPEG")
class JpegRecompressorTest {

    private static final int SIZE = 256;

    /**
     * Градиент с шумом: сплошная заливка в PNG жмётся лучше, чем в JPEG, и проверяла бы не то.
     * Для ARGB альфа везде 255.
     */
    static byte[] photoLikePng(int type) {
        return png(photoLike(type));
    }

    private static BufferedImage photoLike(int type) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, type);
        Random random = new Random(42);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int noise = random.nextInt(16);
                int rgb = ((x + noise) & 0xFF) << 16 | ((y + noise) & 0xFF) << 8 | ((x + y) / 2 & 0xFF);
                image.setRGB(x, y, 0xFF000000 | rgb);
            }
        }
        return image;
    }

    private static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("непрозрачный RGB PNG → JPEG меньше исходника и декодируется")
    void opaquePngBecomesJpeg() throws IOException {
        byte[] png = photoLikePng(BufferedImage.TYPE_INT_RGB);

        InputImage result = JpegRecompressor.compact("image/png", png);

        assertEquals("image/jpeg", result.mime());
        assertTrue(result.bytes().length < png.length);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
        assertNotNull(decoded);
        assertEquals(SIZE, decoded.getWidth());
        assertEquals(SIZE, decoded.getHeight());
    }

    @Test
    @DisplayName("RGBA с альфой 255 везде — обычная кодировка, тоже в JPEG")
    void rgbaWithOpaqueAlphaBecomesJpeg() {
        InputImage result = JpegRecompressor.compact("image/png", photoLikePng(BufferedImage.TYPE_INT_ARGB));

        assertEquals("image/jpeg", result.mime());
    }

    @Test
    @DisplayName("хоть один прозрачный пиксель — PNG остаётся как есть")
    void transparentPngKept() {
        BufferedImage image = photoLike(BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x00000000);
        byte[] png = png(image);

        InputImage result = JpegRecompressor.compact("image/png", png);

        assertEquals("image/png", result.mime());
        assertSame(png, result.bytes());
    }

    @Test
    @DisplayName("палитровый PNG: прозрачный индекс держит PNG, непрозрачная палитра пережимается")
    void indexedPngTransparencyFromPalette() {
        byte[] withTransparentIndex = indexedPng(0);
        byte[] opaquePalette = indexedPng(-1);

        assertEquals("image/png", JpegRecompressor.compact("image/png", withTransparentIndex).mime());
        assertEquals("image/jpeg", JpegRecompressor.compact("image/png", opaquePalette).mime());
    }

    /** 256 серых оттенков в шуме — чтобы PNG не сжался лучше JPEG'а; {@code transparentIndex} −1 — без прозрачности. */
    private static byte[] indexedPng(int transparentIndex) {
        byte[] gray = new byte[256];
        for (int i = 0; i < 256; i++) {
            gray[i] = (byte) i;
        }
        IndexColorModel palette = new IndexColorModel(8, 256, gray, gray, gray, transparentIndex);
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_INDEXED, palette);
        Random random = new Random(42);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                image.getRaster().setSample(x, y, 0, Math.min(255, (x + y) / 2 + random.nextInt(16)));
            }
        }
        return png(image);
    }

    @Test
    @DisplayName("не PNG (jpeg, webp) не трогается")
    void nonPngKept() {
        byte[] bytes = {1, 2, 3};

        assertSame(bytes, JpegRecompressor.compact("image/jpeg", bytes).bytes());
        assertSame(bytes, JpegRecompressor.compact("image/webp", bytes).bytes());
    }

    @Test
    @DisplayName("битый PNG — оригинал, а не исключение")
    void undecodablePngKept() {
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

        InputImage result = JpegRecompressor.compact("image/png", bytes);

        assertEquals("image/png", result.mime());
        assertArrayEquals(bytes, result.bytes());
    }

    @Test
    @DisplayName("JPEG не меньше исходника (сплошная заливка) — оставляем PNG")
    void largerJpegKept() {
        BufferedImage flat = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        byte[] png = png(flat);

        InputImage result = JpegRecompressor.compact("image/png", png);

        assertEquals("image/png", result.mime());
        assertSame(png, result.bytes());
    }
}
