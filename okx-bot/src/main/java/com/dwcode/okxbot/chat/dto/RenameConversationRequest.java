package com.dwcode.okxbot.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重命名会话（兼容旧接口；完整更新请用 {@link UpdateConversationRequest}）。
 */
@Data
public class RenameConversationRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题最多 100 字")
    private String title;
}
