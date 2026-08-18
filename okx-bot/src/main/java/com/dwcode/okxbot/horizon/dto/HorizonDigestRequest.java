package com.dwcode.okxbot.horizon.dto;

import lombok.Data;

/**
 * Horizon webhook 日报体。markdown / summary 二选一。
 */
@Data
public class HorizonDigestRequest {

    /** 可选；入库标题固定为「Horizon 每日速递 {date}」，此字段仅作后备 */
    private String title;

    /** zh / en，默认 zh */
    private String lang;

    /** YYYY-MM-DD，缺省用当天北京时间 */
    private String date;

    /** 日报 Markdown */
    private String markdown;

    /** 与 markdown 同义（Horizon 占位符 #{summary}） */
    private String summary;
}
