package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class EvidenceVerificationService {

    public boolean timestampCovered(VideoContext context, AnalysisResult.Evidence evidence) {
        return context != null && evidence != null && context.segments().stream()
                .anyMatch(segment -> containsTimestamp(segment, evidence.timestampMs()));
    }

    public boolean supported(VideoContext context, AnalysisResult.Evidence evidence) {
        if (context == null || evidence == null || evidence.content().isBlank()) return false;
        String source = evidence.source().toUpperCase(Locale.ROOT);
        // Enforce the model-output contract, not a substring of an invented source.
        if (!source.equals("ASR") && !source.equals("OCR") && !source.equals("ASR+OCR")) return false;

        return context.segments().stream()
                .filter(segment -> containsTimestamp(segment, evidence.timestampMs()))
                .map(segment -> sourceText(segment, source))
                .anyMatch(candidate -> textMatches(evidence.content(), candidate));
    }

    public boolean supportsClaim(VideoContext context,
                                 String claim,
                                 AnalysisResult.Evidence evidence) {
        return evidence != null
                && !normalize(claim).isEmpty()
                && normalize(claim).equals(normalize(evidence.claim()))
                && supported(context, evidence);
    }

    private boolean containsTimestamp(VideoContext.VideoSegment segment, long timestampMs) {
        return timestampMs >= segment.startMs() && timestampMs < segment.endMs();
    }

    private String sourceText(VideoContext.VideoSegment segment, String source) {
        if (source.equals("ASR+OCR")) {
            return segment.transcript() + " " + String.join(" ", segment.ocrTexts());
        }
        if (source.equals("ASR")) return segment.transcript();
        return String.join(" ", segment.ocrTexts());
    }

    private boolean textMatches(String evidence, String candidate) {
        String normalizedEvidence = normalize(evidence);
        String normalizedCandidate = normalize(candidate);
        return !normalizedEvidence.isEmpty()
                && !normalizedCandidate.isEmpty()
                && normalizedCandidate.contains(normalizedEvidence);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
