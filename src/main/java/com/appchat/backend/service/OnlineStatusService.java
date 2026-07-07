package com.appchat.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class OnlineStatusService {

    private static final String ONLINE_KEY_PREFIX = "online:user:";
    private static final Duration ONLINE_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    public void markOnline(String username, String sessionId) {
        if (username == null || username.isBlank()) {
            return;
        }

        try {
            redisTemplate.opsForValue().set(key(username), sessionId == null ? "online" : sessionId, ONLINE_TTL);
        } catch (RedisConnectionFailureException ex) {
            logRedisUnavailable(ex);
        }
    }

    public void markOffline(String username) {
        if (username == null || username.isBlank()) {
            return;
        }

        try {
            redisTemplate.delete(key(username));
        } catch (RedisConnectionFailureException ex) {
            logRedisUnavailable(ex);
        }
    }

    public boolean isOnline(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(username)));
        } catch (RedisConnectionFailureException ex) {
            logRedisUnavailable(ex);
            return false;
        }
    }

    public String statusOf(String username) {
        return isOnline(username) ? "ONLINE" : "OFFLINE";
    }

    private String key(String username) {
        return ONLINE_KEY_PREFIX + username;
    }

    private void logRedisUnavailable(Exception ex) {
        System.err.println("Redis unavailable, using local WebSocket session status only: " + ex.getMessage());
    }
}
