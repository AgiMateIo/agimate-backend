package ru.agimate.userapi.controller.dto.request.auth;

public record LogoutRequest(
        String refreshTokenId
) {
}
