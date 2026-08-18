package com.dwcode.okxbot.horizon.service;

import com.dwcode.okxbot.blog.SlugUtil;
import com.dwcode.okxbot.blog.adapter.HaloHttpPublishAdapter;
import com.dwcode.okxbot.blog.config.HaloProperties;
import com.dwcode.okxbot.blog.port.HaloPublishCommand;
import com.dwcode.okxbot.blog.port.HaloPublishResult;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.horizon.config.HorizonProperties;
import com.dwcode.okxbot.horizon.dto.HorizonDigestRequest;
import com.dwcode.okxbot.horizon.dto.HorizonDigestView;
import com.dwcode.okxbot.horizon.dto.HorizonRefreshStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 拉起 Horizon，读当天公共稿（本地 summaries），可选同步 Halo。不写入个人知识库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HorizonRefreshService {

    private final HorizonProperties properties;
    private final HaloProperties haloProperties;
    private final ObjectMapper objectMapper;
    private final HorizonCliRunner cliRunner;
    private final HorizonSummaryFiles summaryFiles;
    private final HorizonIngestService ingestService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile LocalDateTime lastStartedAt;
    private volatile LocalDateTime lastFinishedAt;
    private volatile boolean lastOk;
    private volatile String lastMessage;
    private volatile boolean lastPublished;
    private volatile String lastPermalink;

    public HorizonRefreshStatus status() {
        return HorizonRefreshStatus.builder()
                .enabled(properties.isRefreshEnabled())
                .running(running.get())
                .lastStartedAt(lastStartedAt)
                .lastFinishedAt(lastFinishedAt)
                .lastOk(lastOk)
                .lastMessage(lastMessage)
                .lastPublished(lastPublished)
                .lastPermalink(lastPermalink)
                .build();
    }

    public HorizonRefreshStatus refresh(boolean force) {
        return refresh(force, HorizonCliRunner.HOURLY_HOURS);
    }

    public HorizonRefreshStatus refresh(boolean force, int hours) {
        if (!force && !properties.isRefreshEnabled()) {
            throw new BusinessException(503, "Horizon 小时刷新未启用");
        }
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(409, "上一轮 Horizon 仍在运行");
        }
        lastStartedAt = LocalDateTime.now();
        lastMessage = "running";
        try {
            if (cliAvailable()) {
                cliRunner.run(hours);
            } else {
                log.info("horizon cli skipped (no Horizon repo here), import summaries only");
            }
            HorizonDigestView view = readTodayFile();
            if (view == null) {
                throw new BusinessException(502, "Horizon 已结束但没有当天中文日报（不会回落到旧稿）");
            }
            HorizonDigestRequest req = new HorizonDigestRequest();
            req.setLang("zh");
            req.setDate(view.getDate());
            req.setMarkdown(view.getMarkdown());
            ingestService.save(req);
            maybePublish(view);
            lastOk = true;
            lastMessage = "ok date=" + view.getDate();
            log.info("horizon refresh ok date={}", view.getDate());
            return status();
        } catch (BusinessException e) {
            lastOk = false;
            lastMessage = e.getMessage();
            throw e;
        } catch (Exception e) {
            lastOk = false;
            lastMessage = e.getMessage();
            log.error("horizon refresh failed: {}", e.getMessage(), e);
            throw new BusinessException(502, "Horizon 刷新失败: " + e.getMessage());
        } finally {
            lastFinishedAt = LocalDateTime.now();
            running.set(false);
        }
    }

    private HorizonDigestView readTodayFile() {
        for (String day : HorizonIngestService.todayDates()) {
            HorizonDigestView view = summaryFiles.latest("zh", day).orElse(null);
            if (view != null) {
                return view;
            }
        }
        return null;
    }

    private boolean cliAvailable() {
        try {
            cliRunner.resolveCliDir();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 只把已有 summaries 写入公共表，不跑 Horizon。线上容器产稿后由本方法入库。 */
    public HorizonRefreshStatus importSummaries() {
        HorizonDigestView view = readTodayFile();
        if (view == null) {
            lastMessage = "no today file yet";
            return status();
        }
        HorizonDigestRequest req = new HorizonDigestRequest();
        req.setLang("zh");
        req.setDate(view.getDate());
        req.setMarkdown(view.getMarkdown());
        ingestService.save(req);
        maybePublish(view);
        lastOk = true;
        lastMessage = "imported " + view.getDate();
        log.info("horizon imported summaries date={}", view.getDate());
        return status();
    }

    /** 启动时：优先发北京日文件；没有文件再发库里的当天稿。 */
    public void publishIfUnpublishedToday() {
        if (readTodayFile() != null) {
            importSummaries();
            return;
        }
        HorizonDigestView view = ingestService.latest("zh");
        if (view == null) {
            return;
        }
        maybePublish(view);
    }

    /** 把已有日报发到 Halo，不重新跑 Horizon。 */
    public HorizonRefreshStatus publishToBlog(String date) {
        HorizonDigestView view = StringUtils.hasText(date)
                ? ingestService.latest("zh", date)
                : ingestService.latest("zh");
        if (view == null || !StringUtils.hasText(view.getMarkdown())) {
            throw new BusinessException(404, "没有可发布的今日资讯");
        }
        if (!haloProperties.isConfigured()) {
            throw new BusinessException(503, "未配置 Halo，无法发博");
        }
        lastStartedAt = LocalDateTime.now();
        try {
            HorizonDigestRequest req = new HorizonDigestRequest();
            req.setLang("zh");
            req.setDate(view.getDate());
            req.setMarkdown(view.getMarkdown());
            ingestService.save(req);
            boolean published = doPublishHalo(view);
            lastOk = published;
            lastPublished = published;
            lastMessage = published ? "published " + view.getDate() : "halo publish returned empty url";
            return status();
        } finally {
            lastFinishedAt = LocalDateTime.now();
        }
    }

    private void maybePublish(HorizonDigestView view) {
        if (!properties.isAutoPublish() || !haloProperties.isConfigured()) {
            lastPublished = false;
            return;
        }
        if (view == null || !hasPublishableItems(view.getMarkdown())) {
            lastPublished = false;
            return;
        }
        try {
            lastPublished = doPublishHalo(view);
        } catch (Exception e) {
            lastPublished = false;
            log.warn("horizon halo publish skipped: {}", e.getMessage());
        }
    }

    static boolean hasPublishableItems(String markdown) {
        return StringUtils.hasText(markdown) && markdown.contains("](http");
    }

    private boolean doPublishHalo(HorizonDigestView view) {
        String title = HorizonIngestService.canonicalTitle(view.getDate(), "zh");
        String slug = SlugUtil.fromTitle(title, "horizon-" + view.getDate());
        String existing = ingestService.haloPostName(view.getDate());
        HaloPublishResult result = new HaloHttpPublishAdapter(haloProperties, objectMapper).publish(
                new HaloPublishCommand(
                        title,
                        slug,
                        view.getMarkdown(),
                        "markdown",
                        existing,
                        List.of("时讯"),
                        List.of("时讯"),
                        null));
        ingestService.rememberHalo(view.getDate(), result.postName(), result.publicUrl());
        lastPermalink = result.publicUrl();
        String href = "/archives/horizon-" + view.getDate();
        try {
            new HaloHttpPublishAdapter(haloProperties, objectMapper).pointMenuItem("资讯", href);
        } catch (Exception e) {
            log.warn("horizon menu 资讯 not updated: {}", e.getMessage());
        }
        return StringUtils.hasText(result.publicUrl());
    }
}
