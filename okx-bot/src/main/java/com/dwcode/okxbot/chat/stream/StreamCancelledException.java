package com.dwcode.okxbot.chat.stream;

/**
 * 用户主动停止流式生成；可携带已生成的部分文本。
 */
public class StreamCancelledException extends RuntimeException {

    private final String partialContent;

    public StreamCancelledException(String partialContent) {
        super("用户停止生成");
        this.partialContent = partialContent != null ? partialContent : "";
    }

    public String getPartialContent() {
        return partialContent;
    }
}
