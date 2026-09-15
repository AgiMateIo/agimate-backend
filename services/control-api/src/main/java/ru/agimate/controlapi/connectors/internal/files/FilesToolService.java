package ru.agimate.controlapi.connectors.internal.files;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.internal.files.dto.FilesDtos.FileList;
import ru.agimate.controlapi.connectors.internal.files.dto.FilesDtos.FileResult;
import ru.agimate.controlapi.connectors.internal.files.dto.FilesDtos.FileView;
import ru.agimate.controlapi.connectors.internal.files.dto.FilesDtos.ReadResult;
import ru.agimate.controlapi.connectors.internal.files.dto.FilesDtos.TextEdit;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.repositories.StoredFileRepository;
import ru.agimate.controlapi.storage.FileIds;
import ru.agimate.controlapi.storage.FileStorageException;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.FileStorageService.FileContent;
import ru.agimate.controlapi.storage.NewFile;
import ru.agimate.controlapi.storage.StoredFileNotFoundException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Tools of the files connector. Every write is a new version of the file (docs/connectors/files.md):
 * the id stays, reading it returns the latest text, and what was already sent keeps showing what was
 * sent. Two writes of one file at once are refused rather than merged — the loser reads again.
 */
@Component
@RequiredArgsConstructor
public class FilesToolService {

    /** A model writes documents, not payloads; anything bigger belongs to a tool that produces it. */
    static final int MAX_CONTENT_BYTES = 1024 * 1024;
    /** One read window: about ten thousand tokens. */
    static final int MAX_READ_CHARS = 40_000;
    static final int DEFAULT_READ_LINES = 1_000;
    static final int MAX_LISTING = 100;

    private static final String ORIGIN = "files";

    private final FileStorageService fileStorageService;
    private final StoredFileRepository storedFileRepository;

    @Tool(name = "save_file",
            description = "Write a text file: a document, a report, an HTML page, a CSV or JSON. Without "
                    + "fileId it creates a new file and name is required; with fileId it replaces the whole "
                    + "contents as a new version under the same id (for small changes use edit_file). "
                    + "The type follows the name's extension: .html, .md, .csv, .json, anything else is "
                    + "plain text. Returns {\"file\": {\"id\": \"agf_…\", …, \"version\"}} — attach it to "
                    + "your reply with [[attach:agf_…]]. Limit 1 MB.",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public FileResult saveFile(
            @ToolParam("The full text of the file") String content,
            @ToolParam(value = "File name with extension, e.g. report.html. Required for a new file; "
                    + "for an existing one it renames it", required = false) String name,
            @ToolParam(value = "Id of an existing file (agf_…) to write a new version of", required = false)
            String fileId) {
        byte[] bytes = contentBytes(content);
        ConnectorEnv env = ConnectorEnvHolder.current();
        String newName = blankToNull(name);
        String existingId = blankToNull(fileId);
        if (existingId == null) {
            if (newName == null) {
                throw new ConnectorException("name is required for a new file, e.g. report.html");
            }
            return guard(() -> result(fileStorageService.store(spec(env, newName,
                    TextFiles.mimeForName(newName), bytes.length), new ByteArrayInputStream(bytes))));
        }
        requireFileId(existingId);
        return guard(() -> {
            StoredFile current = fileStorageService.findReadable(env.userId(), existingId)
                    .orElseThrow(() -> new StoredFileNotFoundException(existingId));
            String mime = newName != null ? TextFiles.mimeForName(newName)
                    : TextFiles.isText(current.getMime()) ? current.getMime()
                    : TextFiles.mimeForName(current.getName());
            return result(fileStorageService.storeVersion(existingId, current.getVersion(),
                    spec(env, newName, mime, bytes.length), bytes));
        });
    }

    @Tool(name = "edit_file",
            description = "Change a text file in place with exact replacements, as a new version under the "
                    + "same id. Each oldText must match the current text exactly — whitespace and line "
                    + "breaks included — and occur exactly once after the previous edits are applied; "
                    + "otherwise nothing is changed and the error says why. Read the file first. "
                    + "Returns {\"file\": {…, \"version\"}}.",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public FileResult editFile(
            @ToolParam("File id (agf_…)") String fileId,
            @ToolParam("Replacements applied in order: [{oldText, newText}]; an empty newText deletes "
                    + "oldText") List<TextEdit> edits) {
        requireFileId(fileId);
        ConnectorEnv env = ConnectorEnvHolder.current();
        return guard(() -> {
            FileContent current = fileStorageService.open(env.userId(), fileId);
            requireText(fileId, current);
            String edited = TextEdits.apply(decodeStrict(fileId, current), edits);
            byte[] bytes = contentBytes(edited);
            return result(fileStorageService.storeVersion(fileId, current.link().version(),
                    spec(env, null, current.mime(), bytes.length), bytes));
        });
    }

    @Tool(name = "read_file",
            description = "Read a text file (agf_… id) by lines. Returns a window of the text with "
                    + "fromLine/toLine/totalLines and nextOffset — pass it as offset to read on; "
                    + "nextOffset is null at the end. Lines longer than 2000 characters are split. "
                    + "For images use media.read_image instead.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public ReadResult readFile(
            @ToolParam("File id (agf_…)") String fileId,
            @ToolParam(value = "Line to start from, 1-based (default 1)", required = false) Integer offset,
            @ToolParam(value = "Maximum lines to return (default 1000)", required = false) Integer limit) {
        requireFileId(fileId);
        ConnectorEnv env = ConnectorEnvHolder.current();
        return guard(() -> {
            FileContent content = fileStorageService.open(env.userId(), fileId);
            requireText(fileId, content);
            List<String> lines = TextFiles.lines(decodeLenient(fileId, content));
            int from = offset == null || offset < 1 ? 1 : offset;
            int maxLines = limit == null || limit < 1 ? DEFAULT_READ_LINES : limit;

            StringBuilder window = new StringBuilder();
            int line = from;
            while (line <= lines.size() && line - from < maxLines) {
                String next = lines.get(line - 1);
                if (line > from && window.length() + 1 + next.length() > MAX_READ_CHARS) {
                    break;
                }
                if (line > from) {
                    window.append('\n');
                }
                window.append(next);
                line++;
            }
            Integer nextOffset = line <= lines.size() ? line : null;
            return new ReadResult(view(content), from, line - 1, lines.size(), window.toString(), nextOffset);
        });
    }

    @Tool(name = "list_files",
            description = "Find a file the owner shared with you or one written earlier — a document, a "
                    + "page, a photo from the conversation, an exported table. Use it when the agf_ id is "
                    + "no longer in what you can see. Freshest first. Files expire, so an old one may "
                    + "simply be gone.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public FileList listFiles(
            @ToolParam(value = "Search the whole account instead of this conversation only "
                    + "(default false)", required = false) Boolean allConversations,
            @ToolParam(value = "Optional substring of the file name", required = false) String name) {
        ConnectorEnv env = ConnectorEnvHolder.current();
        // Outside a channel flow there is no conversation to narrow to, and refusing would leave the
        // agent with no way to reach its files at all.
        UUID sessionId = Boolean.TRUE.equals(allConversations) ? null : env.sessionId();
        var page = storedFileRepository.findVisible(env.userId(), null, sessionId, blankToNull(name),
                LocalDateTime.now(), PageRequest.of(0, MAX_LISTING));
        return new FileList(page.getContent().stream().map(FilesToolService::view).toList(), page.hasNext());
    }

    private static NewFile spec(ConnectorEnv env, String name, String mime, long sizeBytes) {
        return NewFile.builder()
                .userId(env.userId())
                .agentId(env.agentId())
                .sessionId(env.sessionId())
                .origin(ORIGIN)
                .name(name)
                .mime(mime)
                .sizeBytes(sizeBytes)
                .build();
    }

    private static byte[] contentBytes(String content) {
        if (content == null || content.isEmpty()) {
            throw new ConnectorException("content is empty: a file needs at least one character");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONTENT_BYTES) {
            throw new ConnectorException("content is " + bytes.length + " bytes, the limit is "
                    + MAX_CONTENT_BYTES + " (1 MB)");
        }
        return bytes;
    }

    private static void requireFileId(String fileId) {
        if (fileId == null || FileIds.parse(fileId).isEmpty()) {
            throw new ConnectorException("Invalid file id: '" + fileId + "'. Expected agf_<uuid>");
        }
    }

    private static void requireText(String fileId, FileContent content) {
        if (!TextFiles.isText(content.mime())) {
            closeQuietly(content);
            throw new ConnectorException("file " + fileId + " is " + content.mime() + ", not text. "
                    + "For an image use media.read_image; other binary files can only be forwarded "
                    + "by id or attached with [[attach:" + fileId + "]]");
        }
    }

    private static String decodeStrict(String fileId, FileContent content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(readAll(fileId, content)))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ConnectorException("file " + fileId + " is not UTF-8 text and cannot be edited; "
                    + "write the corrected text with save_file instead");
        }
    }

    /** Reading shows what it can: a stray non-UTF-8 byte in an uploaded CSV must not hide the rest. */
    private static String decodeLenient(String fileId, FileContent content) {
        return new String(readAll(fileId, content), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(String fileId, FileContent content) {
        try (InputStream in = content.content()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ConnectorException("Failed to read file " + fileId + ": " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(FileContent content) {
        try {
            content.content().close();
        } catch (IOException ignored) {
            // Nothing was read; the stream only has to be released.
        }
    }

    private static FileResult result(StoredFile file) {
        return new FileResult(view(file));
    }

    private static FileView view(StoredFile file) {
        return new FileView(FileIds.external(file.getId()), file.getName(), file.getMime(),
                file.getSizeBytes(), file.getVersion());
    }

    private static FileView view(FileContent content) {
        return new FileView(content.link().fileId(), content.link().name(), content.mime(), content.size(),
                content.link().version());
    }

    /** The file layer's refusals — not found, quota, a concurrent write — are the agent's to read. */
    private static <T> T guard(Supplier<T> action) {
        try {
            return action.get();
        } catch (FileStorageException e) {
            throw new ConnectorException(e.getMessage(), e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
