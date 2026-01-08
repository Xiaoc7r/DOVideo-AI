package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.MediaFile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {
    // MyBatis-Plus 会自动帮你生成增删改查代码，这里什么都不用写
}