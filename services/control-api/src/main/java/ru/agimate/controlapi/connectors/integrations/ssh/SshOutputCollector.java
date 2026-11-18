package ru.agimate.controlapi.connectors.integrations.ssh;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the first {@link #BYTE_LIMIT} bytes of one SSH stream and silently drops the rest. MINA
 * feeds stdout and stderr independently from its IO thread while the caller waits, so both entry
 * points are synchronized and each stream owns its own collector. Decoding happens once, at the
 * end, with UTF-8 replacement: a character cut in half by the limit becomes U+FFFD instead of
 * failing the whole command.
 */
public final class SshOutputCollector extends OutputStream {

    public static final int BYTE_LIMIT = 32_768;

    private final ByteArrayOutputStream collected = new ByteArrayOutputStream();
    private boolean truncated;

    @Override
    public synchronized void write(int value) {
        if (collected.size() < BYTE_LIMIT) {
            collected.write(value);
        } else {
            truncated = true;
        }
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        if (length <= 0) {
            return;
        }
        int room = BYTE_LIMIT - collected.size();
        if (length <= room) {
            collected.write(bytes, offset, length);
            return;
        }
        if (room > 0) {
            collected.write(bytes, offset, room);
        }
        truncated = true;
    }

    public synchronized byte[] bytes() {
        return collected.toByteArray();
    }

    public synchronized boolean truncated() {
        return truncated;
    }

    public String text() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }
}
