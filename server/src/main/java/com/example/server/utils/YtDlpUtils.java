package com.example.server.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class YtDlpUtils {

    private static final Logger log = LoggerFactory.getLogger(YtDlpUtils.class);

    private final String ytDlpPath;
    private final String ffmpegDir;

    public YtDlpUtils(@Value("${tool.ytdlp.path}") String ytDlpPath,
                      @Value("${tool.ffmpeg.dir}") String ffmpegDir) {
        this.ytDlpPath = ytDlpPath;
        this.ffmpegDir = ffmpegDir;
    }

    public File downloadVideo(String url) throws Exception {
        validatePublicHttpUrl(url);
        Path outputPath = Path.of(System.getProperty("java.io.tmpdir"), UUID.randomUUID() + ".mp4");
        Path logPath = Files.createTempFile("yt-dlp-", ".log");
        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--no-playlist");
        command.add("--socket-timeout");
        command.add("30");
        command.add("--retries");
        command.add("3");
        command.add("--max-filesize");
        command.add("2048M");
        // Prefer the broadly supported H.264/AVC + AAC combination for imported
        // videos. Merely changing an AV1 file's container to MP4 does not make it
        // playable in Safari on every macOS and hardware combination.
        command.add("-f");
        command.add("bv*[vcodec^=avc1][ext=mp4]+ba[acodec^=mp4a][ext=m4a]/b[vcodec^=avc1][ext=mp4]/bv*[vcodec^=avc1]+ba[acodec^=mp4a]");
        command.add("--merge-output-format");
        command.add("mp4");
        command.add("--recode-video");
        command.add("mp4");
        if (ffmpegDir != null && !ffmpegDir.isBlank()) {
            command.add("--ffmpeg-location");
            command.add(ffmpegDir);
        }
        command.add("-o");
        command.add(outputPath.toString());
        command.add(url);

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start();
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("视频链接下载超时");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(outputPath)) {
                String logs = Files.readString(logPath);
                throw new IllegalStateException("yt-dlp 下载失败: " + tail(logs, 2_000));
            }
            log.info("url_video_downloaded host={} bytes={}", URI.create(url).getHost(), Files.size(outputPath));
            return outputPath.toFile();
        } catch (Exception e) {
            Files.deleteIfExists(outputPath);
            throw e;
        } finally {
            Files.deleteIfExists(logPath);
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    private void validatePublicHttpUrl(String value) throws Exception {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅支持合法的公网 HTTP/HTTPS 视频链接");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                throw new IllegalArgumentException("不允许访问本机或内网地址");
            }
        }
    }

    private String tail(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(value.length() - maxLength);
    }
}
