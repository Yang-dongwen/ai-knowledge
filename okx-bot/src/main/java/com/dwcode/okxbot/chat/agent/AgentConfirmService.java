package com.dwcode.okxbot.chat.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.aigen.dto.AigenCreateOptions;
import com.dwcode.okxbot.aigen.dto.AigenCreateRequest;
import com.dwcode.okxbot.aigen.dto.AigenTaskResponse;
import com.dwcode.okxbot.aigen.service.AigenTaskService;
import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.chat.entity.ChatConversationEntity;
import com.dwcode.okxbot.chat.entity.ChatMessageEntity;
import com.dwcode.okxbot.chat.mapper.ChatConversationMapper;
import com.dwcode.okxbot.chat.mapper.ChatMessageMapper;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.imggen.dto.ImgGenCreateOptions;
import com.dwcode.okxbot.imggen.dto.ImgGenCreateRequest;
import com.dwcode.okxbot.imggen.dto.ImgGenTaskResponse;
import com.dwcode.okxbot.imggen.service.ImgGenTaskService;
import com.dwcode.okxbot.kb.dto.NoteCreateRequest;
import com.dwcode.okxbot.kb.dto.NoteResponse;
import com.dwcode.okxbot.kb.service.KbNoteService;
import com.dwcode.okxbot.video.dto.VideoProcessOptions;
import com.dwcode.okxbot.video.dto.VideoProcessRequest;
import com.dwcode.okxbot.video.dto.VideoTaskResponse;
import com.dwcode.okxbot.video.service.VideoProcessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确认/拒绝写工具草案，并真正创建站内任务。
 * 确认/拒绝后会改写原消息中的 [[AGENT_CONFIRM]] 标记，保证刷新后卡片状态正确。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentConfirmService {

    private static final Pattern CONFIRM_BLOCK = Pattern.compile(
            "\\n*\\[\\[AGENT_CONFIRM\\]\\][\\s\\S]*?\\[\\[/AGENT_CONFIRM\\]\\]");

    private final ConfirmTokenService confirmTokenService;
    private final ImgGenTaskService imgGenTaskService;
    private final AigenTaskService aigenTaskService;
    private final VideoProcessService videoProcessService;
    private final KbNoteService kbNoteService;
    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 确认并执行。
     *
     * @param argOverrides 用户在确认卡上修改的参数（可选，覆盖草案同名键）
     * @return tool 风格结果（含 ui）
     */
    public ToolResult confirm(String confirmId, Map<String, Object> argOverrides) {
        long start = System.currentTimeMillis();
        Long userId = SecurityUtils.requireCurrentUserId();
        ConfirmTokenService.PendingConfirm pending = confirmTokenService.consume(confirmId, userId);
        if (pending == null) {
            AgentAudit.confirm("accept_fail", confirmId, userId, null, null, false,
                    System.currentTimeMillis() - start);
            throw new BusinessException(400, "确认已失效或不存在，请重新发起");
        }
        // 校验会话归属
        if (pending.getConversationId() != null) {
            ChatConversationEntity conv = conversationMapper.selectById(pending.getConversationId());
            if (conv == null || conv.getUserId() == null || !conv.getUserId().equals(userId)) {
                AgentAudit.confirm("accept_fail", confirmId, userId, pending.getConversationId(),
                        pending.getToolName(), false, System.currentTimeMillis() - start);
                throw new BusinessException(404, "会话不存在");
            }
        }

        Map<String, Object> finalArgs = mergeAndSanitizeArgs(pending.getToolName(), pending.getArgs(), argOverrides);
        ToolResult result = executeDraft(pending.getToolName(), finalArgs);
        // 把原「待确认」消息改写为结果卡，避免刷新后再次出现确认按钮
        persistSettled(pending, result, pending.getToolName());
        AgentAudit.confirm("accept", confirmId, userId, pending.getConversationId(),
                pending.getToolName(), result != null && result.isOk(),
                System.currentTimeMillis() - start);
        return result;
    }

    /** 兼容旧调用 */
    public ToolResult confirm(String confirmId) {
        return confirm(confirmId, null);
    }

    public boolean reject(String confirmId) {
        long start = System.currentTimeMillis();
        Long userId = SecurityUtils.requireCurrentUserId();
        ConfirmTokenService.PendingConfirm pending = confirmTokenService.reject(confirmId, userId);
        if (pending == null) {
            AgentAudit.confirm("reject_fail", confirmId, userId, null, null, false,
                    System.currentTimeMillis() - start);
            return false;
        }
        log.info("用户拒绝确认草案: confirmId={}, userId={}", confirmId, userId);
        AgentAudit.confirm("reject", confirmId, userId, pending.getConversationId(),
                pending.getToolName(), true, System.currentTimeMillis() - start);

        Map<String, Object> data = new HashMap<>();
        data.put("confirmId", pending.getConfirmId());
        data.put("tool", pending.getToolName());
        data.put("summary", pending.getSummary());
        data.put("args", pending.getArgs());
        data.put("settled", "rejected");

        Map<String, Object> ui = new HashMap<>();
        ui.put("type", "confirm_rejected");
        ui.put("payload", data);

        ToolResult result = ToolResult.builder()
                .ok(true)
                .code("REJECTED")
                .message("已取消操作" + (pending.getSummary() != null ? "：" + pending.getSummary() : ""))
                .data(data)
                .ui(ui)
                .build();
        persistSettled(pending, result, pending.getToolName());
        return true;
    }

    /**
     * 加载历史时：仍带 AGENT_CONFIRM 但 token 已不存在的消息 → 改写为「已失效」，
     * 避免刷新后再次出现可点的确认按钮（历史已确认但未落库的情况也能兜底）。
     */
    public void reconcileStaleConfirms(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        try {
            List<ChatMessageEntity> list = messageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessageEntity>()
                            .eq(ChatMessageEntity::getConversationId, conversationId)
                            .eq(ChatMessageEntity::getRole, "assistant")
                            .like(ChatMessageEntity::getContent, "[[AGENT_CONFIRM]]")
                            .orderByDesc(ChatMessageEntity::getCreatedAt)
                            .last("LIMIT 50"));
            for (ChatMessageEntity msg : list) {
                String confirmId = extractConfirmId(msg.getContent());
                if (confirmId == null || confirmId.isBlank()) {
                    continue;
                }
                if (confirmTokenService.isActive(confirmId)) {
                    continue;
                }
                Map<String, Object> data = new HashMap<>();
                data.put("confirmId", confirmId);
                data.put("settled", "expired");
                Map<String, Object> ui = new HashMap<>();
                ui.put("type", "confirm_expired");
                ui.put("payload", data);
                ToolResult expired = ToolResult.builder()
                        .ok(false)
                        .code("CONFIRM_EXPIRED")
                        .message("该确认已失效或已处理，请重新发起操作")
                        .data(data)
                        .ui(ui)
                        .build();
                rewriteMessageContent(msg, expired, extractToolName(msg.getContent()));
            }
        } catch (Exception e) {
            log.warn("reconcile stale confirms 失败: conv={}, err={}", conversationId, e.getMessage());
        }
    }

    private ToolResult executeDraft(String tool, Map<String, Object> args) {
        Map<String, Object> safeArgs = args != null ? args : Map.of();
        try {
            return switch (tool) {
                case "draft_imggen" -> createImggen(safeArgs);
                case "draft_aigen" -> createAigen(safeArgs);
                case "draft_video_extract" -> createVideoExtract(safeArgs);
                case "draft_create_note" -> createKbNote(safeArgs);
                default -> ToolResult.fail("UNKNOWN", "未知草案工具: " + tool);
            };
        } catch (BusinessException e) {
            return ToolResult.fail("BIZ", e.getMessage() != null ? e.getMessage() : "创建失败");
        } catch (Exception e) {
            log.error("确认执行失败: tool=" + tool, e);
            return ToolResult.fail("ERROR", "创建失败: " + e.getMessage());
        }
    }

    /**
     * 合并草案参数与用户修改，并按工具校验/裁剪。
     */
    private Map<String, Object> mergeAndSanitizeArgs(String tool,
                                                     Map<String, Object> base,
                                                     Map<String, Object> overrides) {
        Map<String, Object> merged = new HashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (overrides != null) {
            for (Map.Entry<String, Object> e : overrides.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                // 只允许覆盖已知字段，防止注入无关键
                if (isAllowedArgKey(tool, e.getKey())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }
        return sanitizeByTool(tool, merged);
    }

    private static boolean isAllowedArgKey(String tool, String key) {
        if (key == null) return false;
        return switch (tool == null ? "" : tool) {
            case "draft_imggen" -> key.equals("prompt") || key.equals("aspectRatio") || key.equals("n")
                    || key.equals("imageModel") || key.equals("imageProvider")
                    || key.equals("llmModel") || key.equals("llmProvider");
            case "draft_aigen" -> key.equals("prompt") || key.equals("aspectRatio") || key.equals("targetDurationSec")
                    || key.equals("imageModel") || key.equals("imageProvider")
                    || key.equals("llmModel") || key.equals("llmProvider");
            case "draft_video_extract" -> key.equals("url")
                    || key.equals("llmModel") || key.equals("llmProvider");
            case "draft_create_note" -> key.equals("title") || key.equals("content")
                    || key.equals("contentFormat");
            default -> false;
        };
    }

    private Map<String, Object> sanitizeByTool(String tool, Map<String, Object> args) {
        Map<String, Object> out = new HashMap<>();
        if ("draft_imggen".equals(tool)) {
            String prompt = str(args, "prompt", "").trim();
            if (prompt.isBlank()) {
                throw new BusinessException(400, "提示词不能为空");
            }
            if (prompt.length() > 2000) {
                prompt = prompt.substring(0, 2000);
            }
            String aspect = str(args, "aspectRatio", "1:1");
            if (!aspect.matches("1:1|16:9|9:16")) {
                aspect = "1:1";
            }
            int n = intArg(args, "n", 1);
            n = Math.max(1, Math.min(4, n));
            out.put("prompt", prompt);
            out.put("aspectRatio", aspect);
            out.put("n", n);
            putIfPresent(out, args, "imageModel", 200);
            putIfPresent(out, args, "imageProvider", 64);
            putIfPresent(out, args, "llmModel", 200);
            putIfPresent(out, args, "llmProvider", 64);
            return out;
        }
        if ("draft_aigen".equals(tool)) {
            String prompt = str(args, "prompt", "").trim();
            if (prompt.isBlank()) {
                throw new BusinessException(400, "提示词不能为空");
            }
            if (prompt.length() > 4000) {
                prompt = prompt.substring(0, 4000);
            }
            String aspect = str(args, "aspectRatio", "9:16");
            if (!aspect.matches("9:16|16:9|1:1")) {
                aspect = "9:16";
            }
            int duration = intArg(args, "targetDurationSec", 15);
            duration = Math.max(5, Math.min(60, duration));
            out.put("prompt", prompt);
            out.put("aspectRatio", aspect);
            out.put("targetDurationSec", duration);
            putIfPresent(out, args, "imageModel", 200);
            putIfPresent(out, args, "imageProvider", 64);
            putIfPresent(out, args, "llmModel", 200);
            putIfPresent(out, args, "llmProvider", 64);
            return out;
        }
        if ("draft_video_extract".equals(tool)) {
            String url = str(args, "url", "").trim();
            if (url.isBlank() || !url.matches("(?i)https?://\\S+")) {
                throw new BusinessException(400, "请填写有效的视频链接（http/https）");
            }
            if (url.length() > 2000) {
                throw new BusinessException(400, "链接过长");
            }
            out.put("url", url);
            putIfPresent(out, args, "llmModel", 200);
            putIfPresent(out, args, "llmProvider", 64);
            return out;
        }
        if ("draft_create_note".equals(tool)) {
            String content = str(args, "content", "").trim();
            if (content.isBlank()) {
                throw new BusinessException(400, "笔记正文不能为空");
            }
            if (content.length() > 100_000) {
                content = content.substring(0, 100_000);
            }
            String title = str(args, "title", "").trim();
            if (title.length() > 200) {
                title = title.substring(0, 200);
            }
            String format = str(args, "contentFormat", "markdown").trim().toLowerCase();
            if (!"html".equals(format) && !"markdown".equals(format)) {
                format = "markdown";
            }
            if (!title.isBlank()) {
                out.put("title", title);
            }
            out.put("content", content);
            out.put("contentFormat", format);
            return out;
        }
        return args != null ? args : Map.of();
    }

    private static void putIfPresent(Map<String, Object> out, Map<String, Object> args, String key, int maxLen) {
        String v = str(args, key, "").trim();
        if (v.isBlank()) {
            return;
        }
        if (v.length() > maxLen) {
            v = v.substring(0, maxLen);
        }
        out.put(key, v);
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        if (args == null || args.get(key) == null) return def;
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private ToolResult createImggen(Map<String, Object> args) {
        ImgGenCreateRequest req = new ImgGenCreateRequest();
        req.setPrompt(str(args, "prompt"));
        ImgGenCreateOptions opt = new ImgGenCreateOptions();
        opt.setAspectRatio(str(args, "aspectRatio", "1:1"));
        Object n = args.get("n");
        if (n instanceof Number num) {
            opt.setN(num.intValue());
        }
        String imageModel = str(args, "imageModel", "").trim();
        String imageProvider = str(args, "imageProvider", "").trim();
        if (!imageModel.isBlank()) {
            opt.setImageModel(imageModel);
        }
        if (!imageProvider.isBlank()) {
            opt.setImageProvider(imageProvider);
        }
        String llmModel = str(args, "llmModel", "").trim();
        String llmProvider = str(args, "llmProvider", "").trim();
        if (!llmModel.isBlank()) {
            opt.setLlmModel(llmModel);
        }
        if (!llmProvider.isBlank()) {
            opt.setLlmProvider(llmProvider);
        }
        req.setOptions(opt);

        ImgGenTaskResponse task = imgGenTaskService.create(req);
        Map<String, Object> data = new HashMap<>();
        data.put("type", "imggen");
        data.put("taskId", task.getId());
        data.put("status", task.getStatus());
        data.put("openPath", "/image-generate");
        data.put("title", task.getTitle());
        data.put("prompt", task.getPrompt());
        if (task.getModel() != null) {
            data.put("model", task.getModel());
        }
        if (task.getProvider() != null) {
            data.put("provider", task.getProvider());
        }

        Map<String, Object> ui = new HashMap<>();
        ui.put("type", "task_created");
        ui.put("payload", data);

        String modelHint = !imageModel.isBlank() ? " · " + imageModel : "";
        return ToolResult.success(
                "文生图任务已创建：#" + task.getId() + "（" + task.getStatus() + "）" + modelHint,
                data,
                ui);
    }

    private ToolResult createAigen(Map<String, Object> args) {
        AigenCreateRequest req = new AigenCreateRequest();
        req.setPrompt(str(args, "prompt"));
        AigenCreateOptions opt = new AigenCreateOptions();
        opt.setAspectRatio(str(args, "aspectRatio", "9:16"));
        Object d = args.get("targetDurationSec");
        if (d instanceof Number num) {
            opt.setTargetDurationSec(num.intValue());
        }
        String imageModel = str(args, "imageModel", "").trim();
        String imageProvider = str(args, "imageProvider", "").trim();
        if (!imageModel.isBlank()) {
            opt.setImageModel(imageModel);
        }
        if (!imageProvider.isBlank()) {
            opt.setImageProvider(imageProvider);
        }
        String llmModel = str(args, "llmModel", "").trim();
        String llmProvider = str(args, "llmProvider", "").trim();
        if (!llmModel.isBlank()) {
            opt.setLlmModel(llmModel);
        }
        if (!llmProvider.isBlank()) {
            opt.setLlmProvider(llmProvider);
        }
        req.setOptions(opt);

        AigenTaskResponse task = aigenTaskService.create(req);
        Map<String, Object> data = new HashMap<>();
        data.put("type", "aigen");
        data.put("taskId", task.getId());
        data.put("status", task.getStatus());
        data.put("openPath", "/video-generate");
        data.put("title", task.getTitle());
        data.put("prompt", task.getPrompt());
        if (!llmModel.isBlank()) {
            data.put("llmModel", llmModel);
        }
        if (!imageModel.isBlank()) {
            data.put("imageModel", imageModel);
        }

        Map<String, Object> ui = new HashMap<>();
        ui.put("type", "task_created");
        ui.put("payload", data);

        return ToolResult.success(
                "AI 视频任务已创建：#" + task.getId() + "（" + task.getStatus() + "）",
                data,
                ui);
    }

    private ToolResult createKbNote(Map<String, Object> args) {
        NoteCreateRequest req = new NoteCreateRequest();
        String title = str(args, "title", "").trim();
        if (!title.isBlank()) {
            req.setTitle(title);
        }
        req.setContent(str(args, "content", ""));
        req.setContentFormat(str(args, "contentFormat", "markdown"));
        NoteResponse note = kbNoteService.create(req);

        Map<String, Object> data = new HashMap<>();
        data.put("type", "kb_note");
        data.put("noteId", note.getId() != null ? String.valueOf(note.getId()) : null);
        data.put("title", note.getTitle());
        data.put("openPath", "/kb");
        data.put("contentFormat", note.getContentFormat());

        Map<String, Object> ui = new HashMap<>();
        ui.put("type", "note_created");
        ui.put("payload", data);

        return ToolResult.success(
                "知识库笔记已创建：「" + (note.getTitle() != null ? note.getTitle() : "未命名") + "」",
                data,
                ui);
    }

    private ToolResult createVideoExtract(Map<String, Object> args) {
        VideoProcessRequest req = new VideoProcessRequest();
        req.setUrl(str(args, "url"));
        VideoProcessOptions opt = new VideoProcessOptions();
        String llmModel = str(args, "llmModel", "").trim();
        String llmProvider = str(args, "llmProvider", "").trim();
        if (!llmModel.isBlank()) {
            opt.setLlmModel(llmModel);
        }
        if (!llmProvider.isBlank()) {
            opt.setLlmProvider(llmProvider);
        }
        req.setOptions(opt);
        VideoTaskResponse task = videoProcessService.submit(req);

        Map<String, Object> data = new HashMap<>();
        data.put("type", "video");
        data.put("taskId", task.getTaskId());
        data.put("status", task.getStatus());
        data.put("openPath", "/video-extract");
        data.put("title", task.getTitle() != null ? task.getTitle() : task.getUrl());
        data.put("prompt", task.getUrl());
        if (!llmModel.isBlank()) {
            data.put("llmModel", llmModel);
        }

        Map<String, Object> ui = new HashMap<>();
        ui.put("type", "task_created");
        ui.put("payload", data);

        return ToolResult.success(
                "视频提取任务已创建：#" + task.getTaskId() + "（" + task.getStatus() + "）",
                data,
                ui);
    }

    /**
     * 优先改写含 confirmId 的原消息；找不到则插入新消息（带 AGENT_RESULT 标记）。
     */
    private void persistSettled(ConfirmTokenService.PendingConfirm pending,
                                ToolResult result,
                                String toolName) {
        if (pending == null || pending.getConversationId() == null) {
            return;
        }
        Long conversationId = pending.getConversationId();
        String confirmId = pending.getConfirmId();
        try {
            ChatMessageEntity target = findConfirmMessage(conversationId, confirmId);
            if (target != null) {
                rewriteMessageContent(target, result, toolName != null ? toolName : pending.getToolName());
            } else {
                // 兜底：找不到原消息时插入一条带结果标记的助手消息
                insertResultMessage(conversationId, result, toolName != null ? toolName : pending.getToolName());
            }
            touchConversation(conversationId);
        } catch (Exception e) {
            log.warn("确认结果落库失败: confirmId={}, err={}", confirmId, e.getMessage());
        }
    }

    private ChatMessageEntity findConfirmMessage(Long conversationId, String confirmId) {
        if (confirmId == null || confirmId.isBlank()) {
            return null;
        }
        List<ChatMessageEntity> list = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .eq(ChatMessageEntity::getRole, "assistant")
                        .like(ChatMessageEntity::getContent, confirmId)
                        .orderByDesc(ChatMessageEntity::getCreatedAt)
                        .last("LIMIT 5"));
        for (ChatMessageEntity m : list) {
            if (m.getContent() != null && m.getContent().contains("[[AGENT_CONFIRM]]")
                    && m.getContent().contains(confirmId)) {
                return m;
            }
        }
        return list.isEmpty() ? null : list.get(0);
    }

    private void rewriteMessageContent(ChatMessageEntity msg, ToolResult result, String toolName) {
        try {
            String markerJson = buildResultMarkerJson(toolName, result);
            String display = result != null && result.getMessage() != null
                    ? result.getMessage()
                    : "操作完成";
            // 去掉旧确认块，再拼展示文案 + 结果标记
            String old = msg.getContent() != null ? msg.getContent() : "";
            String withoutConfirm = CONFIRM_BLOCK.matcher(old).replaceAll("").trim();
            // 原确认提示文案对历史已无意义，直接用结果文案
            String newContent = display + "\n\n[[AGENT_RESULT]]" + markerJson + "[[/AGENT_RESULT]]";
            // 若去掉确认块后仍有用户可见长文，可保留（一般是模型前言）
            if (!withoutConfirm.isBlank()
                    && !withoutConfirm.equals(display)
                    && withoutConfirm.length() > 8
                    && !withoutConfirm.contains("请确认")) {
                // 保留非确认提示的前言
                String preface = withoutConfirm;
                // 去掉与结果重复的尾部
                if (preface.endsWith(display)) {
                    preface = preface.substring(0, preface.length() - display.length()).trim();
                }
                if (!preface.isBlank() && !preface.contains("[[AGENT_")) {
                    newContent = preface + "\n\n" + display
                            + "\n\n[[AGENT_RESULT]]" + markerJson + "[[/AGENT_RESULT]]";
                }
            }
            msg.setContent(newContent);
            messageMapper.updateById(msg);
            log.info("已改写确认消息为结果: messageId={}, tool={}", msg.getId(), toolName);
        } catch (Exception e) {
            log.warn("改写确认消息失败: messageId={}, err={}", msg.getId(), e.getMessage());
        }
    }

    private void insertResultMessage(Long conversationId, ToolResult result, String toolName) {
        try {
            String markerJson = buildResultMarkerJson(toolName, result);
            String display = result != null && result.getMessage() != null
                    ? result.getMessage()
                    : "操作完成";
            ChatMessageEntity msg = new ChatMessageEntity();
            msg.setConversationId(conversationId);
            msg.setRole("assistant");
            msg.setContent(display + "\n\n[[AGENT_RESULT]]" + markerJson + "[[/AGENT_RESULT]]");
            msg.setCreatedAt(LocalDateTime.now());
            messageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("插入确认结果消息失败: {}", e.getMessage());
        }
    }

    private void touchConversation(Long conversationId) {
        try {
            ChatConversationEntity conv = conversationMapper.selectById(conversationId);
            if (conv != null) {
                conv.setUpdatedAt(LocalDateTime.now());
                conversationMapper.updateById(conv);
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    private String buildResultMarkerJson(String toolName, ToolResult result) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("tool", toolName);
        body.put("ok", result != null && result.isOk());
        body.put("message", result != null ? result.getMessage() : null);
        body.put("data", result != null ? result.getData() : null);
        body.put("ui", result != null ? result.getUi() : null);
        return objectMapper.writeValueAsString(body);
    }

    private String extractConfirmId(String content) {
        if (content == null) return null;
        Matcher m = Pattern.compile("\\[\\[AGENT_CONFIRM\\]\\]([\\s\\S]*?)\\[\\[/AGENT_CONFIRM\\]\\]")
                .matcher(content);
        if (!m.find()) return null;
        try {
            JsonNode node = objectMapper.readTree(m.group(1).trim());
            JsonNode id = node.path("data").path("confirmId");
            if (id.isMissingNode() || id.isNull()) {
                id = node.path("ui").path("payload").path("confirmId");
            }
            return id.isMissingNode() || id.isNull() ? null : id.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private String extractToolName(String content) {
        if (content == null) return null;
        Matcher m = Pattern.compile("\\[\\[AGENT_CONFIRM\\]\\]([\\s\\S]*?)\\[\\[/AGENT_CONFIRM\\]\\]")
                .matcher(content);
        if (!m.find()) return null;
        try {
            JsonNode node = objectMapper.readTree(m.group(1).trim());
            if (node.hasNonNull("tool")) {
                return node.get("tool").asText();
            }
            JsonNode t = node.path("data").path("tool");
            return t.isMissingNode() || t.isNull() ? null : t.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Map<String, Object> args, String key) {
        return str(args, key, "");
    }

    private static String str(Map<String, Object> args, String key, String def) {
        if (args == null || args.get(key) == null) return def;
        return String.valueOf(args.get(key));
    }
}
