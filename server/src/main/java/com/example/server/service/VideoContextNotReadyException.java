package com.example.server.service;

public class VideoContextNotReadyException extends RuntimeException {

  public VideoContextNotReadyException() {
    super("视频内容尚未解析完成，请先完成一次 Video Agent 分析");
  }
}
