package com.example.server.controller;

import com.example.server.common.Result;
import com.example.server.dto.FailedTaskView;
import com.example.server.service.AuthService;
import com.example.server.service.FailedAnalysisTaskService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/failed-analysis")
public class AdminController {

  private final FailedAnalysisTaskService failedTaskService;
  private final AuthService authService;

  public AdminController(FailedAnalysisTaskService failedTaskService, AuthService authService) {
    this.failedTaskService = failedTaskService;
    this.authService = authService;
  }

  @GetMapping
  public Result<List<FailedTaskView>> latest(
      @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
    authService.requireAdmin(userId);
    return Result.ok(failedTaskService.latest().stream().map(FailedTaskView::from).toList());
  }

  @PostMapping("/{id}/replay")
  public ResponseEntity<Result<Void>> replay(
      @PathVariable Long id, @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
    authService.requireAdmin(userId);
    failedTaskService.replay(id);
    // 重新入队是异步受理，用 202 表达“已接单”而非“已完成”。
    return ResponseEntity.accepted().body(Result.ok());
  }
}
