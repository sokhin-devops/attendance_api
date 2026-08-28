package com.attendance.api.security;

import com.attendance.api.config.AppProperties;
import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.Role;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies HS256 access tokens, and mints opaque refresh tokens.
 * Refresh tokens are random strings — only their SHA-256 hash is persisted.
 */
@Component
@Slf4j
public class JwtTokenProvider {

    private static final String CLAIM_ORG = "org";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NAME = "name";

    private final SecretKey signingKey;
    private final AppProperties.Jwt config;
    private final SecureRandom random = new SecureRandom();

    public JwtTokenProvider(AppProperties properties) {
        this.config = properties.getJwt();
        this.signingKey = buildKey(config.getSecret());
    }

    /** Accepts either a base64 secret or a raw passphrase of at least 32 bytes. */
    private static SecretKey buildKey(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret must be set and at least 32 bytes long for HS256");
        }
        try {
            byte[] decoded = Decoders.BASE64.decode(secret);
            if (decoded.length >= 32) {
                return Keys.hmacShaKeyFor(decoded);
            }
        } catch (Exception e) {
            // Not base64 — fall through and use the raw bytes.
            log.debug("app.jwt.secret is not base64 ({}); using its raw bytes", e.getMessage());
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofMinutes(config.getAccessTokenMinutes()));

        JwtBuilder builder = Jwts.builder()
                .subject(user.getId().toString())
                .issuer(config.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_NAME, user.getFullName());

        // Super admins are platform-scoped and carry no tenant claim.
        UUID organizationId = user.resolveOrganizationId();
        if (organizationId != null) {
            builder.claim(CLAIM_ORG, organizationId.toString());
        }
        return builder.signWith(signingKey).compact();
    }

    public long getAccessTokenSeconds() {
        return Duration.ofMinutes(config.getAccessTokenMinutes()).toSeconds();
    }

    /** Opaque, high-entropy refresh token. The caller stores only {@link #hash}. */
    public String createRefreshToken() {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Instant refreshTokenExpiry() {
        return Instant.now().plus(Duration.ofDays(config.getRefreshTokenDays()));
    }

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** @return parsed claims, or null when the token is absent, malformed or expired. */
    @Nullable
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(config.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("Rejected expired access token for subject {}", e.getClaims().getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected invalid access token: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Rebuilds the principal from verified claims.
     *
     * <p>A signature-valid token can still be missing a claim — for instance one minted by an
     * earlier version of this service. Each required claim is therefore checked rather than
     * dereferenced blindly, so a malformed token is rejected here instead of failing later
     * with a NullPointerException somewhere in a service.
     */
    public UserPrincipal toPrincipal(Claims claims) {
        String orgClaim = claims.get(CLAIM_ORG, String.class);
        return new UserPrincipal(
                requireUuidClaim(claims.getSubject(), "sub"),
                orgClaim == null ? null : requireUuidClaim(orgClaim, CLAIM_ORG),
                requireClaim(claims.get(CLAIM_EMAIL, String.class), CLAIM_EMAIL),
                null,
                requireClaim(claims.get(CLAIM_NAME, String.class), CLAIM_NAME),
                Role.valueOf(requireClaim(claims.get(CLAIM_ROLE, String.class), CLAIM_ROLE)),
                true);
    }

    @NonNull
    private static String requireClaim(@Nullable String value, String claimName) {
        if (value == null || value.isBlank()) {
            throw new BadCredentialsException("Token is missing the '" + claimName + "' claim");
        }
        return value;
    }

    @NonNull
    private static UUID requireUuidClaim(@Nullable String value, String claimName) {
        String raw = requireClaim(value, claimName);
        UUID parsed;
        try {
            parsed = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new BadCredentialsException(
                    "Token claim '" + claimName + "' is not a valid id", e);
        }
        if (parsed == null) {
            throw new BadCredentialsException("Token claim '" + claimName + "' is not a valid id");
        }
        return parsed;
    }
}
