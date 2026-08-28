package com.attendance.api.dto.auth;

import com.attendance.api.dto.user.UserResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Issued token pair plus the authenticated user profile")
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UserResponse user
) {
    public static AuthResponse of(String access, String refresh, long expiresIn, UserResponse user) {
        return new AuthResponse(access, refresh, "Bearer", expiresIn, user);
    }
}
