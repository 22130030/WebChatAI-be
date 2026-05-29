package com.appchat.backend.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JwtUtil {

    @Value("${app.jwt.secret:appchat_jwt_secret_key_2026_minhcong_project_very_long_123456}")
    private String secret;

    @Value("${app.jwt.expiration-ms:86400000}")
    private long expirationMs;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String generateToken(String username) {
        try {
            long nowSeconds = Instant.now().getEpochSecond();
            long expSeconds = nowSeconds + (expirationMs / 1000);

            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", username);
            payload.put("iat", nowSeconds);
            payload.put("exp", expSeconds);

            String encodedHeader = base64UrlEncode(objectMapper.writeValueAsBytes(header));
            String encodedPayload = base64UrlEncode(objectMapper.writeValueAsBytes(payload));
            String unsignedToken = encodedHeader + "." + encodedPayload;
            String signature = sign(unsignedToken);

            return unsignedToken + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("Cannot generate JWT token", e);
        }
    }

    public String getUsernameFromToken(String token) {
        try {
            Map<String, Object> payload = parsePayload(token);
            Object subject = payload.get("sub");
            return subject == null ? null : subject.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isTokenValid(String token) {
        try {
            if (token == null || token.isBlank()) {
                return false;
            }

            token = cleanToken(token);
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }

            String unsignedToken = parts[0] + "." + parts[1];
            String expectedSignature = sign(unsignedToken);

            if (!MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8)
            )) {
                return false;
            }

            Map<String, Object> payload = parsePayload(token);
            Object subject = payload.get("sub");
            Object exp = payload.get("exp");

            if (subject == null || subject.toString().isBlank() || exp == null) {
                return false;
            }

            long expSeconds = ((Number) exp).longValue();
            return Instant.now().getEpochSecond() < expSeconds;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> parsePayload(String token) throws Exception {
        token = cleanToken(token);
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT token");
        }

        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        return objectMapper.readValue(payloadBytes, new TypeReference<Map<String, Object>>() {});
    }

    private String sign(String unsignedToken) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] signatureBytes = mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(signatureBytes);
    }

    private String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String cleanToken(String token) {
        token = token.trim();
        if (token.startsWith("Bearer ")) {
            return token.substring(7).trim();
        }
        return token;
    }
}
