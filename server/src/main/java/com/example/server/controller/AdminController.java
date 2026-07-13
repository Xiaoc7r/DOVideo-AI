package com.example.server.controller;

import com.example.server.entity.FailedAnalysisTask;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import com.example.server.service.AuthService;
import com.example.server.service.FailedAnalysisTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/failed-analysis")
public class AdminController {

    private final FailedAnalysisTaskService failedTaskService;
    private final UserMapper userMapper;

    public AdminController(FailedAnalysisTaskService failedTaskService, UserMapper userMapper) {
        this.failedTaskService = failedTaskService;
        this.userMapper = userMapper;
    }

    @GetMapping
    public List<FailedAnalysisTask> latest(
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        requireAdmin(userId);
        return failedTaskService.latest();
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<String> replay(
            @PathVariable Long id,
            @RequestAttribute(AuthService.REQUEST_USER_ID) Long userId) {
        requireAdmin(userId);
        failedTaskService.replay(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body("失败任务已重新入队");
    }

    private void requireAdmin(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || !"ADMIN".equals(user.getRole())) {
            throw new SecurityException("仅管理员可操作失败任务");
        }
    }
}
