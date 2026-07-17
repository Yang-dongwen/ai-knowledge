package com.dwcode.okxbot.aigen.port;

/**
 * 图生视频防腐层：静图 → 可播短视频片段。
 * 实现：本地 kinetic（FFmpeg）/ 可选云端 SVD 等。
 */
public interface ImageToVideoPort {

    ImageToVideoResult convert(ImageToVideoCommand command);
}
