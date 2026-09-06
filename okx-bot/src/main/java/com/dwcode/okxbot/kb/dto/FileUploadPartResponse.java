package com.dwcode.okxbot.kb.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FileUploadPartResponse {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long uploadId;

    private int partNumber;
    private long partBytes;
    private List<Integer> receivedParts;
    private long uploadedBytes;
    private int totalParts;
}
