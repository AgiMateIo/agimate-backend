package ru.agimate.controlapi.service.llm.media;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.controlapi.service.llm.media.MediaTransport.InputImage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Generated PNGs become JPEGs before they reach storage: a PNG of a 1K picture weighs megabytes, and
 * neither Polza nor OpenRouter lets most image models (GPT-5.4 Image 2, Gemini) choose the format, so
 * it is decided here — once, for every model and transport. An optimization, never a failure: whatever
 * cannot be recompressed safely is kept exactly as the provider returned it.
 */
@UtilityClass
@Slf4j
class JpegRecompressor {

    static final String PNG = "image/png";
    static final String JPEG = "image/jpeg";
    /** These models render text; at 0.9 the gain comes from the format switch, not from visible artefacts. */
    static final float JPEG_QUALITY = 0.9f;
    /**
     * A 4K square. The decoded picture sits in the heap twice (source + RGB copy, up to 4 bytes a pixel
     * each), and a 20 MB PNG can hide far more pixels than that.
     */
    static final long MAX_PIXELS = 4096L * 4096;

    /** JPEG bytes for an opaque PNG; the original for anything else, including a larger JPEG. */
    static InputImage compact(String mime, byte[] bytes) {
        InputImage original = new InputImage(mime, bytes);
        if (!PNG.equalsIgnoreCase(mime)) {
            return original;
        }
        try {
            byte[] jpeg = recompress(bytes);
            return jpeg == null || jpeg.length >= bytes.length ? original : new InputImage(JPEG, jpeg);
        } catch (IOException | RuntimeException e) {
            // RuntimeException too: a malformed PNG can break the decoder with anything, and the file is still usable.
            log.warn("PNG recompression failed, keeping the original: {}", e.toString());
            return original;
        }
    }

    /** {@code null} when the picture has to stay a PNG: undecodable, too many pixels, or transparent. */
    private static byte[] recompress(byte[] png) throws IOException {
        BufferedImage source = decode(png);
        if (source == null || hasTransparency(source)) {
            return null;
        }
        // The JPEG writer rejects alpha and misreads indexed/16-bit models — draw onto plain RGB first.
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return encodeJpeg(rgb);
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        // Memory-cached streams: the default ones spill into java.io.tmpdir for no gain.
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > MAX_PIXELS) {
                    log.info("PNG of {} pixels exceeds the recompression cap, keeping the original", pixels);
                    return null;
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Real transparency only: an RGBA PNG whose alpha is all 255 is a common encoding and still a JPEG candidate. */
    private static boolean hasTransparency(BufferedImage image) {
        WritableRaster alpha = image.getAlphaRaster();
        if (alpha == null) {
            // An indexed PNG keeps transparency in the palette (tRNS), which has no alpha band to scan.
            return image.getColorModel().getTransparency() != Transparency.OPAQUE;
        }
        int max = (1 << alpha.getSampleModel().getSampleSize(0)) - 1;
        int[] row = new int[alpha.getWidth()];
        for (int y = 0; y < alpha.getHeight(); y++) {
            alpha.getSamples(0, y, alpha.getWidth(), 1, 0, row);
            for (int sample : row) {
                if (sample < max) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] encodeJpeg(BufferedImage rgb) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(buffer)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(out);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return buffer.toByteArray();
    }
}
