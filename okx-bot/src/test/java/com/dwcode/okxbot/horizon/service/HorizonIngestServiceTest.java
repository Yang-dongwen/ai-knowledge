package com.dwcode.okxbot.horizon.service;

import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.horizon.config.HorizonProperties;
import com.dwcode.okxbot.horizon.dto.HorizonDigestRequest;
import com.dwcode.okxbot.horizon.dto.HorizonDigestView;
import com.dwcode.okxbot.horizon.entity.HorizonDigestEntity;
import com.dwcode.okxbot.horizon.mapper.HorizonDigestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HorizonIngestServiceTest {

    private HorizonProperties properties;
    private HorizonDigestMapper digestMapper;
    private HorizonSummaryFiles files;
    private HorizonIngestService svc;

    @BeforeEach
    void setUp() {
        properties = new HorizonProperties();
        properties.setToken("secret-token");
        digestMapper = mock(HorizonDigestMapper.class);
        files = mock(HorizonSummaryFiles.class);
        when(files.latest(any(), any())).thenReturn(java.util.Optional.empty());
        when(files.recent(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(java.util.List.of());
        svc = new HorizonIngestService(properties, digestMapper, files);
    }

    @Test
    void rejectsWhenNoToken() {
        properties.setToken("");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.ingest("secret-token", req("zh", "2026-08-16", "# hi")));
        assertEquals(503, ex.getCode());
    }

    @Test
    void rejectsBadToken() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.ingest("wrong", req("zh", "2026-08-16", "# hi")));
        assertEquals(401, ex.getCode());
    }

    @Test
    void saveInsertsPlatformRow() {
        when(digestMapper.selectById("2026-08-16")).thenReturn(null);
        var resp = svc.save(req("zh", "2026-08-16", "# hi"));
        assertTrue(resp.isCreated());
        verify(digestMapper).insert(any());
        verify(digestMapper, never()).updateById(any());
    }

    @Test
    void latestReadsDbNotPersonalKb() {
        HorizonDigestEntity row = new HorizonDigestEntity();
        row.setDigestDate("2026-08-16");
        row.setLang("zh");
        row.setTitle("Horizon 每日速递 2026-08-16");
        row.setMarkdown("# hi");
        when(digestMapper.selectById("2026-08-16")).thenReturn(row);
        HorizonDigestView view = svc.latest("zh", "2026-08-16");
        assertEquals("2026-08-16", view.getDate());
        assertEquals("# hi", view.getMarkdown());
        assertNull(view.getNoteId());
    }

    @Test
    void todayDatesIsShanghaiCalendarOnly() {
        String shanghai = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        assertEquals(java.util.List.of(shanghai), HorizonIngestService.todayDates());
    }

    @Test
    void hasTodayIgnoresUtcYesterdayRow() {
        String shanghai = HorizonIngestService.shanghaiToday();
        when(digestMapper.selectById(shanghai)).thenReturn(null);
        assertFalse(svc.hasToday("zh"));

        HorizonDigestEntity row = new HorizonDigestEntity();
        row.setDigestDate(shanghai);
        row.setLang("zh");
        when(digestMapper.selectById(shanghai)).thenReturn(row);
        assertTrue(svc.hasToday("zh"));
    }

    @Test
    void saveDecodesApostropheEntities() {
        when(digestMapper.selectById("2026-08-17")).thenReturn(null);
        svc.save(req("zh", "2026-08-17", "Anthropic 的 &\\#x27;水印&\\#x27; 文本"));
        org.mockito.ArgumentCaptor<HorizonDigestEntity> cap =
                org.mockito.ArgumentCaptor.forClass(HorizonDigestEntity.class);
        verify(digestMapper).insert(cap.capture());
        assertEquals("Anthropic 的 '水印' 文本", cap.getValue().getMarkdown());
        assertTrue(cap.getValue().getSnippet().contains("'水印'"));
    }

    @Test
    void englishTitle() {
        assertEquals("Horizon Daily 2026-08-16", HorizonIngestService.canonicalTitle("2026-08-16", "en"));
        assertEquals("Horizon 每日速递 2026-08-16", HorizonIngestService.canonicalTitle("2026-08-16", "zh"));
    }

    private static HorizonDigestRequest req(String lang, String date, String md) {
        HorizonDigestRequest r = new HorizonDigestRequest();
        r.setLang(lang);
        r.setDate(date);
        r.setMarkdown(md);
        return r;
    }
}
