package ru.agimate.userapi.controller.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.PageResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.UserRole;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.userapi.controller.dto.request.UpdateUserRoleRequest;
import ru.agimate.userapi.controller.dto.response.UserResponse;
import ru.agimate.userapi.mappers.UserMapper;
import ru.agimate.userapi.service.UserService;

import java.util.UUID;

@RestController
@RequestMapping(AdminUserController.PATH)
@RequiredArgsConstructor
@Tag(name = "Admin: Users", description = "The user directory and their roles (admins only)")
public class AdminUserController {

    public static final String PATH = AdminPaths.PREFIX + "/users";

    private final UserService userService;

    @Operation(summary = "List users", description = "Newest first; optionally filtered by role and by a "
            + "case-insensitive substring of email or display name")
    @GetMapping("/")
    public SuccessResponse<PageResponse<UserResponse>> listUsers(
            @Parameter(description = "Substring of email or display name")
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserRole role,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return SuccessResponse.ok(PageResponse.from(
                userService.listUsers(search, role, page, size).map(UserMapper::getUserResponse)));
    }

    @Operation(summary = "Change a user's role",
            description = "Changing your own role is rejected. Note that control-api reads the role from "
                    + "the access token, so a demotion reaches it only after the token is refreshed")
    @PatchMapping("/{id}/role")
    public SuccessResponse<UserResponse> changeRole(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Parameter(description = "ID of the user whose role is changed", required = true)
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateUserRoleRequest request
    ) {
        UUID actorId = UUID.fromString(principal.id());
        return SuccessResponse.ok(UserMapper.getUserResponse(
                userService.changeRole(actorId, id, request.role())));
    }
}
