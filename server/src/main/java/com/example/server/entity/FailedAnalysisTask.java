package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("failed_analysis_tasks")
public class FailedAnalysisTask {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long mediaId;
  private String action;
  private String mode;
  private String contentHash;
  private String userGoal;
  private Integer attemptCount;
  private String errorType;
  private String errorMessage;
  private String status;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
