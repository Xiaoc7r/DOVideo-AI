package com.example.server.dto;

public record AuthRequest(String username, String password, String nickname) {
}
