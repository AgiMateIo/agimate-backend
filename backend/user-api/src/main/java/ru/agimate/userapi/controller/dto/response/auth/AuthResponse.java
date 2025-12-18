package ru.agimate.userapi.controller.dto.response.auth;

public record AuthResponse(
        String accessToken,
        String refreshTokenId
) {

}