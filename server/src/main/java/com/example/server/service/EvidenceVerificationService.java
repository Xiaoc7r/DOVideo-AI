package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class EvidenceVerificationService {

    private static final double MIN_BIGRAM_COVERAGE = 0.5;

    public boolean timestampCovered(VideoContext context, AnalysisResult.Evidence evidence) {
        return context != null && evidence != null && context.segments().stream()
                .anyMatch(segment -> containsTimestamp(segment, evidence.timestampMs()));
    }

    public boolean supported(VideoContext context, AnalysisResult.Evidence evidence) {
        if (context == null || evidence == null || evidence.content().isBlank()) return false;
        String source = evidence.source().toUpperCase(Locale.ROOT);
        if (!source.contains("ASR") && !source.contains("OCR")) return false;

        return context.segments().stream()
                .filter(segment -> containsTimestamp(segment, evidence.timestampMs()))
                .map(segment -> sourceText(segment, source))
                .anyMatch(candidate -> textMatches(evidence.content(), candidate));
    }

    private boolean containsTimestamp(VideoContext.VideoSegment segment, long timestampMs) {
        return timestampMs >= segment.startMs() && timestampMs < segment.endMs();
    }

    private String sourceText(VideoContext.VideoSegment segment, String source) {
        if (source.contains("ASR")) return segment.transcript();
        return String.join(" ", segment.ocrTexts());
    }

    private boolean textMatches(String evidence, String candidate) {
        String normalizedEvidence = normalize(evidence);
        String normalizedCandidate = normalize(candidate);
        if (normalizedEvidence.isEmpty() || normalizedCandidate.isEmpty()) return false;
        if (normalizedCandidate.contains(normalizedEvidence)
                || normalizedEvidence.contains(normalizedCandidate)) {
            return true;
        }
        if (normalizedEvidence.length() < 4 || normalizedCandidate.length() < 4) return false;

        Set<String> evidenceBigrams = bigrams(normalizedEvidence);
        Set<String> candidateBigrams = bigrams(normalizedCandidate);
        long overlap = evidenceBigrams.stream().filter(candidateBigrams::contains).count();
        return (double) overlap / evidenceBigrams.size() >= MIN_BIGRAM_COVERAGE;
    }

    private Set<String> bigrams(String value) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < value.length() - 1; i++) {
            result.add(value.substring(i, i + 2));
        }
        return result;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
