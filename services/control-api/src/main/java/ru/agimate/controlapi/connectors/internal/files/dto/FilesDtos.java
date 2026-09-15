package ru.agimate.controlapi.connectors.internal.files.dto;

import java.util.List;

/** Parameters and results of the files connector's tools. */
public final class FilesDtos {

    private FilesDtos() {
    }

    /** @param newText may be empty — that deletes {@code oldText} */
    public record TextEdit(String oldText, String newText) {
    }

    /**
     * A file by the docs/connectors/files.md convention: {@code id} goes into another tool's parameter
     * or into {@code [[attach:agf_…]]}.
     *
     * @param name {@code null} where the file never had one (a photo from a chat, a generated image)
     */
    public record FileView(String id, String name, String mime, long size, int version) {
    }

    public record FileResult(FileView file) {
    }

    /**
     * A window of a text file.
     *
     * @param fromLine   first line in {@code content}, 1-based
     * @param toLine     last line in {@code content}; below {@code fromLine} when the window is empty
     * @param nextOffset where the next window starts; {@code null} — the end of the file was reached
     */
    public record ReadResult(FileView file, int fromLine, int toLine, int totalLines, String content,
                             Integer nextOffset) {
    }

    public record FileList(List<FileView> files, boolean truncated) {
    }
}
