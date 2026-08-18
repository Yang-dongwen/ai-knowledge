package com.dwcode.okxbot.horizon.service;

import com.dwcode.okxbot.blog.config.HaloProperties;
import com.dwcode.okxbot.horizon.config.HorizonProperties;
import com.dwcode.okxbot.horizon.dto.HorizonDigestView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HorizonRefreshServiceTest {

    @Test
    void doesNotWritePersonalNotes() throws Exception {
        HorizonProperties props = new HorizonProperties();
        props.setRefreshEnabled(true);
        HorizonCliRunner cli = mock(HorizonCliRunner.class);
        HorizonSummaryFiles files = mock(HorizonSummaryFiles.class);
        when(files.latest(any(), any())).thenReturn(Optional.of(HorizonDigestView.builder()
                .date("2026-08-16")
                .markdown("# hi")
                .build()));

        HorizonIngestService ingest = mock(HorizonIngestService.class);
        HorizonRefreshService svc = new HorizonRefreshService(
                props, new HaloProperties(), new ObjectMapper(), cli, files, ingest);
        var status = svc.refresh(false);
        assertTrue(status.isLastOk());
        assertFalse(status.isLastPublished());
        verify(cli).run(24);
        verify(ingest).save(any());
    }

    @Test
    void emptyDigestIsNotPublishable() {
        assertFalse(HorizonRefreshService.hasPublishableItems(null));
        assertFalse(HorizonRefreshService.hasPublishableItems("> 已分析 1 条内容，但没有达到重要性阈值的条目。"));
        assertTrue(HorizonRefreshService.hasPublishableItems(
                "### [标题](https://example.com/a)\n"));
    }
}
