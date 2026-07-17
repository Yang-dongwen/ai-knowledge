package com.dwcode.okxbot.aigen.service;

import com.dwcode.okxbot.aigen.domain.shot.ShotDto;
import com.dwcode.okxbot.aigen.domain.shot.ShotlistDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主题相关性：从用户提示词提取锚点词，检查镜头 visual.prompt 是否扣题。
 */
@Service
public class TopicRelevanceService {

    private static final Pattern LATIN = Pattern.compile("[A-Za-z][A-Za-z0-9\\-\\.]{1,40}");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,12}");

    private static final Set<String> STOP_LATIN = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "video", "make", "create",
            "about", "into", "your", "will", "have", "been", "were", "are", "was", "history",
            "generate", "please", "short", "film", "story"
    );

    private static final Set<String> STOP_CJK = Set.of(
            "生成", "视频", "一下", "我们", "什么", "怎么", "如何", "一个", "这个", "那个",
            "进行", "关于", "历史", "进程", "故事", "短片", "画面", "内容", "主题", "介绍"
    );

    /**
     * 提取主题锚点（专有名词 / 实质词），保序去重，最多 12 个。
     */
    public List<String> extractAnchors(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        String text = userPrompt.trim();

        Matcher lm = LATIN.matcher(text);
        while (lm.find()) {
            String w = lm.group();
            String low = w.toLowerCase(Locale.ROOT);
            if (STOP_LATIN.contains(low)) {
                continue;
            }
            // 保留原始大小写形态（ETH / Ethereum）
            out.add(w);
            if (out.size() >= 12) {
                return new ArrayList<>(out);
            }
        }

        Matcher cm = CJK_RUN.matcher(text);
        while (cm.find()) {
            String w = cm.group();
            if (STOP_CJK.contains(w)) {
                continue;
            }
            // 过滤纯虚词短串
            if (w.length() == 2 && STOP_CJK.contains(w)) {
                continue;
            }
            out.add(w);
            if (out.size() >= 12) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 检查每镜 prompt / promptEn 是否至少命中一个锚点。
     *
     * @return 错误描述列表（空=通过）
     */
    public List<String> validateShotlist(ShotlistDto list, String userPrompt) {
        List<String> anchors = extractAnchors(userPrompt);
        List<String> errors = new ArrayList<>();
        if (anchors.isEmpty() || list == null || list.getShots() == null) {
            return errors;
        }
        for (int i = 0; i < list.getShots().size(); i++) {
            ShotDto s = list.getShots().get(i);
            String zh = s.getVisual() != null ? nullToEmpty(s.getVisual().getPrompt()) : "";
            String en = s.getVisual() != null ? nullToEmpty(s.getVisual().getPromptEn()) : "";
            String blob = (zh + " " + en).toLowerCase(Locale.ROOT);
            boolean hit = false;
            for (String a : anchors) {
                if (a == null || a.isBlank()) {
                    continue;
                }
                if (blob.contains(a.toLowerCase(Locale.ROOT))) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                errors.add("shots[" + i + "] 画面描述未命中主题关键词 " + anchors
                        + "（请把主题主体写入 visual.prompt / promptEn）");
            }
        }
        return errors;
    }

    /**
     * 生成「主题锚点」英文/中文前缀，供出图注入。
     */
    public String buildAnchorPrefix(String userPrompt, boolean englishPreferred) {
        List<String> anchors = extractAnchors(userPrompt);
        if (anchors.isEmpty()) {
            return "";
        }
        String joined = String.join(", ", anchors.subList(0, Math.min(6, anchors.size())));
        if (englishPreferred) {
            return "Main subject must depict: " + joined + ". ";
        }
        return "画面主体必须体现：" + joined + "。";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
