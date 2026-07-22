package com.dwcode.okxbot.article.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ArticlePasteRequest {
    @NotBlank(message = "pasteText 不能为空")
    private String pasteText;
}
