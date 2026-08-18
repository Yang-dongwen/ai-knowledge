package com.dwcode.okxbot.horizon.service;

import com.dwcode.okxbot.horizon.config.HorizonProperties;
import com.dwcode.okxbot.horizon.dto.HorizonDigestBrief;
import com.dwcode.okxbot.horizon.dto.HorizonDigestView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HorizonFeedService {

    private final HorizonIngestService ingestService;
    private final HorizonProperties properties;

    public String rss(String lang) {
        String newsUrl = properties.newsPublicUrl();
        List<HorizonDigestView> digests = new ArrayList<>();
        for (HorizonDigestBrief brief : ingestService.recent(lang, 7)) {
            if (brief == null || brief.getDate() == null) {
                continue;
            }
            HorizonDigestView view = ingestService.latest(lang, brief.getDate());
            if (view != null) {
                digests.add(view);
            }
        }
        var items = HorizonFeedBuilder.items(digests, newsUrl);
        return HorizonFeedBuilder.rss("今日资讯", newsUrl, "Horizon 每日速递", items);
    }
}
