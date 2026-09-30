package com.example.server.utils;

import com.example.server.dto.AnalysisMode;

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
        return sha256(goal.trim());
    }

    /**
     * 模式感知的目标摘要:任务身份 = (媒体记录, 目标, 模式),结果复用范围 = (内容, 目标, 模式)。
     *
     * <p>GENERAL 直接委托 {@link #goalDigest(String)},摘要逐字节不变,与引入模式前的既有缓存/键
     * 完全兼容;其余模式把模式名并入摘要,使"同一目标文本、不同模式"落在不同的 checkpoint / 去重 /
     * 状态键上,互不污染。null 模式按 GENERAL 处理,保证历史消息与遗漏调用安全降级。
     */
    public static String goalDigest(String goal, AnalysisMode mode) {
        if (mode == null || mode == AnalysisMode.GENERAL) {
            return goalDigest(goal);
        }
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("analysis goal is required");
        }
        // U+241F 是不可见的单元分隔符,避免"模式名+目标"与某个真实目标文本发生摘要碰撞。
        return sha256(mode.name() + '␟' + goal.trim());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String active(String contentHash, String goalDigest) {
        return "analysis:active:" + contentHash + ":" + goalDigest;
    }

    /** Submission, retry and revision ownership belongs to a media record, not shared content. */
    public static String mediaScope(Long mediaId) {
        return "media-" + mediaId;
    }

    public static String lock(String contentHash, String goalDigest) {
        return "lock:analysis:" + contentHash + ":" + goalDigest;
    }

    public static String completed(String contentScope, String goalDigest) {
        return "analysis:completed:" + contentScope + ":" + goalDigest;
    }

    public static String attempts(String contentScope, String goalDigest) {
        return "analysis:attempts:" + contentScope + ":" + goalDigest;
    }

    /**
     * 内容级预处理的归属键：记录哪个 mediaId already 产出过该内容的 VideoContext。
     * ASR/OCR 只取决于视频内容本身，与用户目标无关，因此按 contentHash 而非 goal 复用。
     */
    public static String contextOwner(String contentHash) {
        return "analysis:context-owner:" + contentHash;
    }

    /**
     * 内容级预处理锁：同一视频被不同目标同时提交时，只允许一个消费者真正跑 ASR/OCR，
     * 其余等待后直接复用，避免重复烧算力与第三方额度。
     */
    public static String contextLock(String contentHash) {
        return "lock:analysis-context:" + contentHash;
    }
}
