package com.appchat.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnlineStatusService {

    private static final String ONLINE_KEY_PREFIX = "online:user:";
    private static final Duration ONLINE_TTL = Duration.ofMinutes(10);
    private static final long REDIS_COOLDOWN_MS = 10000; // 10 seconds cooldown

    private final StringRedisTemplate redisTemplate;
    private final Map<String, Long> localOnlineUntil = new ConcurrentHashMap<>();

    private volatile boolean redisAvailable = true;
    private volatile long lastFailureTime = 0;

    public void markOnline(String username, String sessionId) {
        if (username == null || username.isBlank()) {
            return;
        }
        localOnlineUntil.put(username, System.currentTimeMillis() + ONLINE_TTL.toMillis());

        if (!checkRedisAvailable()) {
            return;
        }

        try {
            redisTemplate.opsForValue().set(key(username), sessionId == null ? "online" : sessionId, ONLINE_TTL);
        } catch (Exception ex) {
            handleRedisFailure(ex);
        }
    }

    public void markOffline(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        localOnlineUntil.remove(username);

        if (!checkRedisAvailable()) {
            return;
        }

        try {
            redisTemplate.delete(key(username));
        } catch (Exception ex) {
            handleRedisFailure(ex);
        }
    }

    public boolean isOnline(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }

        if (isLocallyOnline(username)) {
            return true;
        }

        if (!checkRedisAvailable()) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(username)));
        } catch (Exception ex) {
            handleRedisFailure(ex);
            return false;
        }
    }

    public String statusOf(String username) {
        return isOnline(username) ? "ONLINE" : "OFFLINE";
    }

    private String key(String username) {
        return ONLINE_KEY_PREFIX + username;
    }

    private boolean isLocallyOnline(String username) {
        Long onlineUntil = localOnlineUntil.get(username);

        if (onlineUntil == null) {
            return false;
        }

        if (onlineUntil >= System.currentTimeMillis()) {
            return true;
        }

        localOnlineUntil.remove(username);
        return false;
    }

    private boolean checkRedisAvailable() {
        if (!redisAvailable) {
            if (System.currentTimeMillis() - lastFailureTime > REDIS_COOLDOWN_MS) {
                // Cooldown period expired, try to reuse Redis
                redisAvailable = true;
                return true;
            }
            return false;
        }
        return true;
    }

    private void handleRedisFailure(Exception ex) {
        redisAvailable = false;
        lastFailureTime = System.currentTimeMillis();
        log.warn("Redis unavailable, using local WebSocket session status only: {}", ex.getMessage());
    }
}

