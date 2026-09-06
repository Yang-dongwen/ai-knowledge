package com.dwcode.okxbot.kb.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class FileUploadSessionResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long uploadId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long noteId;

    private String originalName;
    private String contentType;
    private String kind;
    private long totalBytes;
    private int chunkSize;
    private int totalParts;
    private String status;
    /** 已成功落盘的分片号（1-based），用于断点续传 */
    private List<Integer> receivedParts;
    private long uploadedBytes;
    private LocalDateTime expiresAt;
}
