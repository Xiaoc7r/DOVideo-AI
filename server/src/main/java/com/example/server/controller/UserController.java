package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
//加上这个是为了防止跨域问题漏网
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class UserController {

    @Autowired(required = false)
    private UserMapper userMapper;

    //注册接口
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody User user) {
        Map<String, Object> result = new HashMap<>();
        try {
            //打印日志，确认数据进来了
            System.out.println("收到注册请求: " + user.getUsername());

            //检查 Mapper 是否注入成功
            if (userMapper == null) {
                throw new RuntimeException("UserMapper 未注入，请检查 @Mapper 注解！");
            }

            QueryWrapper<User> query = new QueryWrapper<>();
            query.eq("username", user.getUsername());
            if (userMapper.selectCount(query) > 0) {
                result.put("code", 400);
                result.put("msg", "该账号已存在");
                return result;
            }

            //默认角色
            if (user.getNickname() == null || user.getNickname().isEmpty()) {
                user.setNickname("用户" + System.currentTimeMillis());
            }
            user.setRole("USER");

            userMapper.insert(user); //关键动作

            result.put("code", 200);
            result.put("msg", "注册成功");
            result.put("data", user);
        } catch (Exception e) {
            //如果在黑窗口看到这个报错，就知道原因了
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "后端报错: " + e.getMessage());
        }
        return result;
    }

    //登录接口
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody User loginUser) {
        Map<String, Object> result = new HashMap<>();
        try {
            System.out.println("收到登录请求: " + loginUser.getUsername());

            QueryWrapper<User> query = new QueryWrapper<>();
            query.eq("username", loginUser.getUsername());
            query.eq("password", loginUser.getPassword());

            User dbUser = userMapper.selectOne(query);

            if (dbUser == null) {
                result.put("code", 401);
                result.put("msg", "账号或密码错误");
            } else {
                result.put("code", 200);
                result.put("msg", "登录成功");
                result.put("token", "user_" + dbUser.getId());
                result.put("userInfo", dbUser);
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "登录报错: " + e.getMessage());
        }
        return result;
    }
}