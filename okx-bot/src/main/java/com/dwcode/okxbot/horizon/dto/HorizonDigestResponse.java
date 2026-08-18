package com.dwcode.okxbot.horizon.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HorizonDigestResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long noteId;

    private String title;

    /** true=新建，false=同日更新 */
    private boolean created;

    private boolean published;

    private String haloPermalink;
}
