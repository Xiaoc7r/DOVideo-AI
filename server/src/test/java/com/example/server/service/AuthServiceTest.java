package com.example.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.server.dto.AuthRequest;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class AuthServiceTest {
  @Test
  void usernameCaseCannotResetTheFailedLoginBudgetAndSuccessClearsTheSameKey() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    Map<String, Long> failures = new HashMap<>();
    when(values.get(anyString()))
        .thenAnswer(
            call -> {
              Long value = failures.get(call.getArgument(0));
              return value == null ? null : value.toString();
            });
    when(redis.execute(any(RedisScript.class), anyList(), any()))
        .thenAnswer(
            call -> {
              RedisScript<?> script = call.getArgument(0);
              assertTrue(script.getScriptAsString().contains("redis.call('INCR'"));
              assertTrue(script.getScriptAsString().contains("redis.call('EXPIRE'"));
              assertEquals("600", call.getArgument(2));
              return failures.merge(
                  call.getArgument(1, List.class).getFirst().toString(), 1L, Long::sum);
            });
    UserMapper users = mock(UserMapper.class);
    AuthService service = new AuthService(redis, users);
    for (int attempt = 0; attempt < 8; attempt++) {
      assertEquals(
          401,
          service
              .login(new AuthRequest(attempt % 2 == 0 ? "TestUser" : "tESTuSER", "wrong", null))
              .code());
    }
    assertEquals(429, service.login(new AuthRequest("TESTUSER", "wrong", null)).code());
    assertEquals(Map.of("auth:login-failures:testuser", 8L), failures);
    verify(users, times(8)).selectOne(any());
    service.clearLoginFailures("TestUser");
    verify(redis).delete("auth:login-failures:testuser");
    verify(values, never()).increment(anyString());
  }

  @Test
  void oversizedPasswordIsRejectedBeforeDatabaseOrPasswordHashWork() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    UserMapper users = mock(UserMapper.class);
    AuthService service = new AuthService(redis, users);
    assertEquals(400, service.login(new AuthRequest("user", "x".repeat(129), null)).code());
    verifyNoInteractions(users, redis);
  }

  @Test
  void legacyShortPasswordsStillLoginAndUpgradeToSaltedHashes() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
    UserMapper users = mock(UserMapper.class);
    User user = new User();
    user.setId(7L);
    user.setUsername("User");
    user.setPassword("old");
    user.setRole("USER");
    when(users.selectOne(any())).thenReturn(user);
    AuthService service = new AuthService(redis, users);
    var result = service.login(new AuthRequest("User", "old", null));
    assertEquals(200, result.code());
    assertEquals(43, result.token().length());
    assertTrue(service.isHashed(user.getPassword()));
    assertTrue(service.passwordMatches("old", user.getPassword()));
    assertFalse(service.passwordMatches("other", user.getPassword()));
    verify(users).updateById(user);
    verify(redis).delete("auth:login-failures:user");
  }
}
