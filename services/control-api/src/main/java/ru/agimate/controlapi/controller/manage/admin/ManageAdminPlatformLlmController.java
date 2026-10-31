package ru.agimate.controlapi.controller.manage.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.PlatformLlmResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.UpdatePlatformLlmRequest;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.service.PlatformLlmService;

import java.util.List;

/**
 * The platform's ordered chains per purpose. The whole {@code /manage/admin} prefix is gated by
 * {@code ROLE_ADMIN} in {@code SecurityConfig}, so no method-level {@code @PreAuthorize} is needed.
 */
@RestController
@RequestMapping(ManageAdminPlatformLlmController.PATH)
@RequiredArgsConstructor
@Tag(name = "Platform LLM purposes", description = "Ordered platform model chains per purpose (ADMIN)")
public class ManageAdminPlatformLlmController {

    public static final String PATH = ManageAdminPaths.PREFIX + "/platform-llms";

    private final PlatformLlmService platformLlmService;

    @Operation(summary = "List platform LLM bindings in purpose and priority order")
    @GetMapping("/")
    public SuccessResponse<List<PlatformLlmResponse>> list() {
        return SuccessResponse.ok(platformLlmService.list());
    }

    @Operation(summary = "Replace the ordered chain of a platform purpose",
            description = "An empty list removes the purpose and is equivalent to DELETE")
    @PutMapping("/{purpose}")
    public SuccessResponse<List<PlatformLlmResponse>> replace(
            @PathVariable LlmPurpose purpose,
            @Valid @RequestBody UpdatePlatformLlmRequest request
    ) {
        List<PlatformLlmService.Binding> bindings = request.bindings().stream()
                .map(b -> new PlatformLlmService.Binding(b.llmProviderId(), b.model()))
                .toList();
        return SuccessResponse.ok(platformLlmService.replaceChain(purpose, bindings));
    }

    @Operation(summary = "Delete the binding of a platform purpose")
    @DeleteMapping("/{purpose}")
    public SuccessResponse<Void> delete(@PathVariable LlmPurpose purpose) {
        platformLlmService.delete(purpose);
        return SuccessResponse.empty();
    }
}
