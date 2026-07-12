package com.example.server.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class OcrUtils {

    private static final Logger log = LoggerFactory.getLogger(OcrUtils.class);

    private final String ocrCommand;

    public OcrUtils(@Value("${tool.ocr.command:tesseract}") String ocrCommand) {
        this.ocrCommand = ocrCommand;
    }

    public String recognize(File image) {
        if (image == null || !image.isFile()) throw new IllegalArgumentException("OCR image does not exist");
        Process process = null;
        Path output = null;
        try {
            output = Files.createTempFile("dovideo-ocr-", ".txt");
            process = new ProcessBuilder(
                    ocrCommand, image.getAbsolutePath(), "stdout", "-l", "chi_sim+eng")
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("OCR execution timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("OCR process failed with exit code " + process.exitValue());
            }
            return Files.readString(output, StandardCharsets.UTF_8).trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OCR execution interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("OCR failed for " + image.getName(), e);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (output != null) {
                try {
                    Files.deleteIfExists(output);
                } catch (Exception e) {
                    log.warn("ocr_output_cleanup_failed path={}", output, e);
                }
            }
        }
    }
}
