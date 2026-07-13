package com.example.server.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AnalysisTaskKeys {

    private static final Pattern MD5_PATTERN = Pattern.compile("[a-fA-F0-9]{32}");

    private AnalysisTaskKeys() {
    }

    public static String normalizeContentHash(Long mediaId, String contentHash) {
        if (contentHash != null && MD5_PATTERN.matcher(contentHash).matches()) {
            return contentHash.toLowerCase(Locale.ROOT);
        }
        return "media-" + mediaId;
    }

    public static String goalDigest(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("analysis goal is required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(goal.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String active(String contentHash, String goalDigest) {
        return "analysis:active:" + contentHash + ":" + goalDigest;
    }

    public static String lock(String contentHash, String goalDigest) {
        return "lock:analysis:" + contentHash + ":" + goalDigest;
    }

    public static String completed(String contentScope, String goalDigest) {
        return "analysis:completed:" + contentScope + ":" + goalDigest;
    }
}
