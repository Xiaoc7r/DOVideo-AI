package com.example.server.dto;

import java.io.Serializable;

//必须实现Serializable接口，否则不能在网络上传输
public class AnalysisTaskMsg implements Serializable {
    private Long mediaId;
    private String action; //例如"START_ANALYSIS"

    public AnalysisTaskMsg() {}

    public AnalysisTaskMsg(Long mediaId, String action) {
        this.mediaId = mediaId;
        this.action = action;
    }

    public Long getMediaId() { return mediaId; }
    public void setMediaId(Long mediaId) { this.mediaId = mediaId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
}