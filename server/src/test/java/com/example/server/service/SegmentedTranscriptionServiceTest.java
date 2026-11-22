package com.example.server.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.server.utils.AliyunAsrUtils;
import com.example.server.utils.MediaProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SegmentedTranscriptionServiceTest {
  @TempDir Path directory;

  @Test
  void numericFileOrderAndOriginalIndexesPreserveLongAndSparseAudioTimelines() throws Exception {
    for (String file :
        List.of("audio_1000.mp3", "audio_099.mp3", "audio_100.mp3", "audio_101.mp3", "notes.txt")) {
      Files.writeString(directory.resolve(file), "audio");
    }
    AliyunAsrUtils asr = mock(AliyunAsrUtils.class);
    when(asr.audioToText(anyString()))
        .thenAnswer(call -> Path.of(call.getArgument(0, String.class)).getFileName().toString());
    var service =
        new SegmentedTranscriptionService(
            asr, mock(AgentTelemetry.class), mock(MediaProcessRunner.class));
    var result = service.transcribe("source", directory, null);
    assertEquals(
        List.of(99 * 60_000L, 100 * 60_000L, 101 * 60_000L, 1000 * 60_000L),
        result.stream().map(part -> part.startMs()).toList());
    assertEquals(
        List.of("audio_099.mp3", "audio_100.mp3", "audio_101.mp3", "audio_1000.mp3"),
        result.stream().map(part -> part.text()).toList());
    verify(asr, times(4)).audioToText(anyString());
  }
}
