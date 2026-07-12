package com.example.server.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    public static final String REQUEST_USER_ID = "authenticatedUserId";

    private static final String PASSWORD_PREFIX = "pbkdf2";
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int PASSWORD_KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int TOKEN_BYTES = 32;
    private static final long SESSION_HOURS = 24;
    private static final String SESSION_PREFIX = "auth:session:";
    private static final String LOGIN_FAILURE_PREFIX = "auth:login-failures:";
    private static final int MAX_LOGIN_FAILURES = 8;
    private static final long LOGIN_FAILURE_WINDOW_MINUTES = 10;

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String hashPassword(String password) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = derive(password.toCharArray(), salt, PASSWORD_ITERATIONS);
        return String.join("$",
                PASSWORD_PREFIX,
                String.valueOf(PASSWORD_ITERATIONS),
                Base64.getEncoder().withoutPadding().encodeToString(salt),
                Base64.getEncoder().withoutPadding().encodeToString(hash));
    }

    public boolean passwordMatches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null) return false;
        if (!isHashed(storedPassword)) {
            return MessageDigest.isEqual(
                    rawPassword.getBytes(StandardCharsets.UTF_8),
                    storedPassword.getBytes(StandardCharsets.UTF_8));
        }

        try {
            String[] parts = storedPassword.split("\\$", -1);
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(rawPassword.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean isHashed(String password) {
        return password != null && password.startsWith(PASSWORD_PREFIX + "$");
    }

    public String createSession(Long userId) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        redisTemplate.opsForValue().set(sessionKey(token), String.valueOf(userId), SESSION_HOURS, TimeUnit.HOURS);
        return token;
    }

    public Long resolveUser(String authorization) {
        String token = bearerToken(authorization);
        String userId = redisTemplate.opsForValue().get(sessionKey(token));
        if (userId == null) throw new SecurityException("登录状态已失效");
        return Long.valueOf(userId);
    }

    public void revokeSession(String authorization) {
        redisTemplate.delete(sessionKey(bearerToken(authorization)));
    }

    public boolean loginAttemptAllowed(String username) {
        String value = redisTemplate.opsForValue().get(LOGIN_FAILURE_PREFIX + username);
        return value == null || Long.parseLong(value) < MAX_LOGIN_FAILURES;
    }

    public void recordLoginFailure(String username) {
        String key = LOGIN_FAILURE_PREFIX + username;
        Long failures = redisTemplate.opsForValue().increment(key);
        if (failures != null && failures == 1) {
            redisTemplate.expire(key, LOGIN_FAILURE_WINDOW_MINUTES, TimeUnit.MINUTES);
        }
    }

    public void clearLoginFailures(String username) {
        redisTemplate.delete(LOGIN_FAILURE_PREFIX + username);
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new SecurityException("请先登录");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.length() < 32) throw new SecurityException("无效的登录凭证");
        return token;
    }

    private String sessionKey(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return SESSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成会话摘要", e);
        }
    }

    private byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, PASSWORD_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("密码哈希失败", e);
        } finally {
            spec.clearPassword();
        }
    }
}
