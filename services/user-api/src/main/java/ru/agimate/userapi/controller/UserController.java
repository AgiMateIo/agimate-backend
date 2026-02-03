package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.userapi.controller.dto.response.UserResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.userapi.mappers.UserMapper;
import ru.agimate.userapi.service.UserService;

import java.util.UUID;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Tag(name = "User", description = "User management endpoints")
public class UserController {

    private final UserService userService;

    @Operation(summary = "Get user by public ID", description = "Returns user information by their public ID")
    @GetMapping("/{pub_id}")
    @PreAuthorize("@userSecurityService.canAccessUser(authentication, #pubId)")
    public ResponseEntity<SuccessResponse<UserResponse>> getUserByPubId(
            @Parameter(description = "Public ID of the user", required = true)
            @PathVariable("pub_id") UUID pubId) {

        return userService.findByPubId(pubId)
                .map(UserMapper::getUserResponse)
                .map(ur -> ResponseEntity.ok(SuccessResponse.ok(ur)))
                .orElse(ResponseEntity.notFound().build());

    }

    @Operation(summary = "Get current user info", description = "Returns information about the currently authenticated user")
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SuccessResponse<UserResponse>> getCurrentUser(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();

            if (principal instanceof AgimateUserPrincipal agimateUserPrincipal) {
                // Find the user by their pubId to get the full user object
                return userService.findByPubId(UUID.fromString(agimateUserPrincipal.pubId()))
                        .map(UserMapper::getUserResponse)
                        .map(ur -> ResponseEntity.ok(SuccessResponse.ok(ur)))
                        .orElse(ResponseEntity.status(403).build());
            }
        }

        return ResponseEntity.status(401).build();
    }


}