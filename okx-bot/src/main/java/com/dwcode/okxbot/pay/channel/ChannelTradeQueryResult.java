package com.dwcode.okxbot.pay.channel;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChannelTradeQueryResult {
    private boolean paid;
    private String tradeNo;
    private long amountCents;
    private String rawTradeState;
}
