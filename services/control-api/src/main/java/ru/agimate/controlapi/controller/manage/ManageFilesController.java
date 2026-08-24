package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.agimate.common.rest.PageResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.files.FileListItemResponse;
import ru.agimate.controlapi.service.file.UserFileService;

import java.util.UUID;

/**
 * The user's own files (docs/connectors/files.md): upload, listing, deletion. Contents are not
 * served from here: every item carries a signed link to {@code GET /files/{fileId}}, the one public
 * download path.
 */
@RestController
@RequestMapping(ManageFilesController.PATH)
@RequiredArgsConstructor
@Tag(name = "Files", description = "Manage stored files")
public class ManageFilesController {

    public static final String PATH = "/manage/files";

    private final UserFileService userFileService;

    @Operation(
            summary = "Upload a file",
            description = "Puts a file into the file layer under the current user. The returned id "
                    + "(agf_…) is the reference every consumer takes — a webchat attachment "
                    + "(parts: [{\"fileId\": …}]), a tool parameter. Uploading does not attach the "
                    + "file to anything by itself. The optional origin form field labels the place "
                    + "the upload came from in the client's own terms (chat, board): it is stored as "
                    + "user:<origin>, matching [a-z0-9][a-z0-9_-]{0,31}."
    )
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SuccessResponse<FileListItemResponse> uploadFile(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String origin
    ) {
        return SuccessResponse.ok(userFileService.upload(UUID.fromString(principal.id()), file, origin));
    }

    @Operation(
            summary = "List files",
            description = "Returns the current user's files — fully uploaded and not yet expired — "
                    + "freshest first. agentId selects the files related to an agent: the ones it "
                    + "produced and the ones it saw (attachments of that conversation). sessionId "
                    + "selects one conversation, name is a substring of the file name. Each item "
                    + "carries a freshly signed content URL."
    )
    @GetMapping("/")
    public SuccessResponse<PageResponse<FileListItemResponse>> getFiles(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(PageResponse.from(
                userFileService.list(userId, agentId, sessionId, name, page, size)));
    }

    @Operation(
            summary = "Delete a file",
            description = "Removes one of the current user's files ahead of its TTL. References to it "
                    + "from chat history or task comments stop resolving, exactly as they do on expiry."
    )
    @DeleteMapping("/{fileId}")
    public SuccessResponse<Void> deleteFile(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable String fileId
    ) {
        userFileService.delete(UUID.fromString(principal.id()), fileId);
        return SuccessResponse.empty();
    }
}
