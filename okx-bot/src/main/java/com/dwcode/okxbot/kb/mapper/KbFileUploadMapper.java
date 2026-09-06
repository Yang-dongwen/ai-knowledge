package com.dwcode.okxbot.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dwcode.okxbot.kb.entity.KbFileUploadEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface KbFileUploadMapper extends BaseMapper<KbFileUploadEntity> {

    @Update("UPDATE kb_file_upload SET status = #{toStatus}, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND status = #{fromStatus}")
    int casStatus(@Param("id") Long id,
                  @Param("fromStatus") String fromStatus,
                  @Param("toStatus") String toStatus);
}
