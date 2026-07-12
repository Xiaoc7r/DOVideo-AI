package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.AuthRequest;
import com.example.server.dto.AuthResponse;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import com.example.server.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final int MAX_NICKNAME_LENGTH = 50;

    private final UserMapper userMapper;
    private final AuthService authService;

    public UserController(UserMapper userMapper, AuthService authService) {
        this.userMapper = userMapper;
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@RequestBody AuthRequest request) {
        String username = normalizeUsername(request.username());
        String password = request.password();
        if (username == null || password == null || password.length() < 8
                || password.length() > MAX_PASSWORD_LENGTH) {
            return response(400, "账号需为 3-32 位字母、数字或下划线，密码需为 8-128 位", null, null);
        }

        String nickname = normalizeNickname(request.nickname());
        if (nickname == null) {
            return response(400, "昵称不能超过 50 个字符", null, null);
        }

        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", username);
        if (userMapper.selectCount(query) > 0) {
            return response(409, "该账号已存在", null, null);
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(authService.hashPassword(password));
        user.setNickname(nickname.isBlank() ? "用户" + System.currentTimeMillis() : nickname);
        user.setRole("USER");
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            return response(409, "该账号已存在", null, null);
        }
        log.info("user_registered userId={} username={}", user.getId(), username);
        return response(200, "注册成功", userView(user), null);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        String username = normalizeUsername(request.username());
        if (username == null || request.password() == null || request.password().isBlank()) {
            return response(400, "请输入账号和密码", null, null);
        }
        if (!authService.loginAttemptAllowed(username)) {
            return response(429, "登录尝试过于频繁，请稍后再试", null, null);
        }

        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("username", username);
        User dbUser = userMapper.selectOne(query);
        if (dbUser == null || !authService.passwordMatches(request.password(), dbUser.getPassword())) {
            authService.recordLoginFailure(username);
            return response(401, "账号或密码错误", null, null);
        }

        if (!authService.isHashed(dbUser.getPassword())) {
            dbUser.setPassword(authService.hashPassword(request.password()));
            userMapper.updateById(dbUser);
        }
        authService.clearLoginFailures(username);
        String token = authService.createSession(dbUser.getId());
        log.info("user_logged_in userId={}", dbUser.getId());
        return response(200, "登录成功", userView(dbUser), token);
    }

    @PostMapping("/logout")
    public ResponseEntity<AuthResponse> logout(@RequestHeader("Authorization") String authorization) {
        authService.revokeSession(authorization);
        return response(200, "已退出登录", null, null);
    }

    private String normalizeUsername(String username) {
        if (username == null) return null;
        String normalized = username.trim();
        return normalized.matches("[A-Za-z0-9_]{3,32}") ? normalized : null;
    }

    private String normalizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return "";
        String normalized = nickname.trim();
        return normalized.length() <= MAX_NICKNAME_LENGTH ? normalized : null;
    }

    private AuthResponse.UserInfo userView(User user) {
        return new AuthResponse.UserInfo(
                user.getId(), user.getUsername(), user.getNickname(), user.getAvatar(), user.getRole());
    }

    private ResponseEntity<AuthResponse> response(int code,
                                                  String message,
                                                  AuthResponse.UserInfo userInfo,
                                                  String token) {
        return ResponseEntity.status(code).body(new AuthResponse(code, message, userInfo, token));
    }
}
