package com.dwcode.okxbot.pay.channel;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NotifyParseResult {
    private boolean signatureValid;
    /** 仅渠道交易成功态为 true */
    private boolean paid;
    private String orderNo;
    private String tradeNo;
    /** 统一为分 */
    private long amountCents;
    private String appIdOrMchIdHint;
    private String rawTradeState;
}
