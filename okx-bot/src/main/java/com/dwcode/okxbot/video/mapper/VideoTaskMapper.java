package com.dwcode.okxbot.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.video.entity.VideoTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 视频处理任务 Mapper。
 */
@Mapper
public interface VideoTaskMapper extends BaseMapper<VideoTaskEntity> {
}
