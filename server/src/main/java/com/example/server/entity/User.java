package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.ToString;

@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @JsonIgnore
    @ToString.Exclude
    private String password;

    private String nickname;

    private String avatar;

    private String role;

}
