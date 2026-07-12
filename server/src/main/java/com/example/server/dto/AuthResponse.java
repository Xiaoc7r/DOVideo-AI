package com.example.server.dto;

public record AuthResponse(int code, String msg, UserInfo userInfo, String token) {

    public record UserInfo(Long id, String username, String nickname, String avatar, String role) {
    }
}
