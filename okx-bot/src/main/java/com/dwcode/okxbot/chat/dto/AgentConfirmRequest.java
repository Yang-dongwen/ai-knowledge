package com.dwcode.okxbot.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class AgentConfirmRequest {

    @NotBlank(message = "confirmId 不能为空")
    private String confirmId;

    /**
     * 可选：用户在确认卡上修改后的参数。
     * 会与草案参数合并（覆盖同名键），并按工具规则校验。
     */
    private Map<String, Object> args;
}
