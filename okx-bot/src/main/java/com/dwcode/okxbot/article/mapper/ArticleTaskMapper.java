package com.dwcode.okxbot.article.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.article.entity.ArticleTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文章提取任务 Mapper。
 */
@Mapper
public interface ArticleTaskMapper extends BaseMapper<ArticleTaskEntity> {
}
