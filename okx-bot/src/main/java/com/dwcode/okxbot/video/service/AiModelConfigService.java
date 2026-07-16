package com.dwcode.okxbot.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwcode.okxbot.chat.config.AiProperties;
import com.dwcode.okxbot.chat.config.AiProperties.ProviderConfig;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.video.dto.AiModelConfigRequest;
import com.dwcode.okxbot.video.dto.AiModelConfigResponse;
import com.dwcode.okxbot.video.entity.AiModelConfigEntity;
import com.dwcode.okxbot.video.mapper.AiModelConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 模型配置服务（数据库驱动）。
 * capability=chat：对话/润色/分镜；capability=image：文生图；capability=video_omni：视频多模态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelConfigService {

    public static final String CAP_CHAT = "chat";
    public static final String CAP_IMAGE = "image";
    public static final String CAP_VIDEO_OMNI = "video_omni";

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiModelConfigMapper aiModelConfigMapper;
    private final AiProperties aiProperties;

    /**
     * 管理列表：全部模型（含禁用）；可按 capability 过滤。
     *
     * @param capability 空=全部；chat / image / video_omni
     */
    public List<AiModelConfigResponse> listAll(String capability) {
        LambdaQueryWrapper<AiModelConfigEntity> q = new LambdaQueryWrapper<AiModelConfigEntity>()
                .orderByAsc(AiModelConfigEntity::getSortOrder)
                .orderByDesc(AiModelConfigEntity::getCreatedAt);
        String cap = normalizeCapability(capability, false);
        if (cap != null) {
            applyCapabilityFilter(q, cap);
        }
        List<AiModelConfigEntity> list = aiModelConfigMapper.selectList(q);
        return list.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /** 兼容旧调用：返回全部 */
    public List<AiModelConfigResponse> listAll() {
        return listAll(null);
    }

    /**
     * 任务选择用：仅启用的模型，且对应供应商在 yml 中有 api-key。
     * 默认只返回 chat，避免文生图模型混入视频/聊天下拉。
     */
    public List<Map<String, Object>> listEnabledGroupedByProvider() {
        return listEnabledGroupedByProvider(CAP_CHAT);
    }

    public List<Map<String, Object>> listEnabledGroupedByProvider(String capability) {
        String cap = normalizeCapability(capability, true);
        if (cap == null) {
            cap = CAP_CHAT;
        }
        LambdaQueryWrapper<AiModelConfigEntity> q = new LambdaQueryWrapper<AiModelConfigEntity>()
                .eq(AiModelConfigEntity::getEnabled, 1)
                .orderByAsc(AiModelConfigEntity::getSortOrder)
                .orderByDesc(AiModelConfigEntity::getCreatedAt);
        applyCapabilityFilter(q, cap);

        List<AiModelConfigEntity> list = aiModelConfigMapper.selectList(q);

        Map<String, List<AiModelConfigEntity>> byProvider = new LinkedHashMap<>();
        for (AiModelConfigEntity e : list) {
            String p = e.getProvider();
            ProviderConfig pc = aiProperties.getProvider(p);
            if (pc == null || pc.getApiKey() == null || pc.getApiKey().isEmpty()) {
                continue;
            }
            byProvider.computeIfAbsent(p, k -> new ArrayList<>()).add(e);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<AiModelConfigEntity>> entry : byProvider.entrySet()) {
            ProviderConfig pc = aiProperties.getProvider(entry.getKey());
            Map<String, Object> providerMap = new HashMap<>();
            providerMap.put("key", entry.getKey());
            providerMap.put("name", pc != null && pc.getName() != null ? pc.getName() : entry.getKey());
            List<Map<String, Object>> models = new ArrayList<>();
            for (AiModelConfigEntity m : entry.getValue()) {
                Map<String, Object> mm = new HashMap<>();
                mm.put("id", m.getModelId());
                mm.put("name", m.getModelName() != null ? m.getModelName() : m.getModelId());
                mm.put("capability", resolveCapability(m));
                if (m.getInvokeUrl() != null) {
                    mm.put("invokeUrl", m.getInvokeUrl());
                }
                if (m.getDefaultSteps() != null) {
                    mm.put("defaultSteps", m.getDefaultSteps());
                }
                if (m.getMaxSteps() != null) {
                    mm.put("maxSteps", m.getMaxSteps());
                }
                models.add(mm);
            }
            providerMap.put("models", models);
            result.add(providerMap);
        }
        return result;
    }

    /**
     * 启用的文生图模型（扁平列表，供 imggen 下拉）。
     */
    public List<AiModelConfigEntity> listEnabledImageEntities() {
        LambdaQueryWrapper<AiModelConfigEntity> q = new LambdaQueryWrapper<AiModelConfigEntity>()
                .eq(AiModelConfigEntity::getEnabled, 1)
                .orderByAsc(AiModelConfigEntity::getSortOrder)
                .orderByDesc(AiModelConfigEntity::getCreatedAt);
        applyCapabilityFilter(q, CAP_IMAGE);
        List<AiModelConfigEntity> list = aiModelConfigMapper.selectList(q);
        List<AiModelConfigEntity> out = new ArrayList<>();
        for (AiModelConfigEntity e : list) {
            ProviderConfig pc = aiProperties.getProvider(e.getProvider());
            if (pc == null || pc.getApiKey() == null || pc.getApiKey().isEmpty()) {
                continue;
            }
            if (e.getInvokeUrl() == null || e.getInvokeUrl().isBlank()) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    /**
     * 解析启用的文生图模型；modelId 为空时取排序第一。
     */
    public AiModelConfigEntity requireEnabledImageModel(String provider, String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            LambdaQueryWrapper<AiModelConfigEntity> q = new LambdaQueryWrapper<AiModelConfigEntity>()
                    .eq(AiModelConfigEntity::getModelId, modelId.trim())
                    .eq(AiModelConfigEntity::getEnabled, 1);
            applyCapabilityFilter(q, CAP_IMAGE);
            if (provider != null && !provider.isBlank()) {
                q.eq(AiModelConfigEntity::getProvider, provider.trim());
            }
            q.orderByAsc(AiModelConfigEntity::getSortOrder).last("LIMIT 1");
            AiModelConfigEntity e = aiModelConfigMapper.selectOne(q);
            if (e == null) {
                throw new BusinessException(400, "生图模型不存在或未启用: " + modelId
                        + "（请在模型管理中添加 capability=image 的配置）");
            }
            if (e.getInvokeUrl() == null || e.getInvokeUrl().isBlank()) {
                throw new BusinessException(400, "生图模型未配置 invokeUrl: " + modelId);
            }
            ProviderConfig pc = aiProperties.getProvider(e.getProvider());
            if (pc == null || pc.getApiKey() == null || pc.getApiKey().isBlank()) {
                throw new BusinessException(400, "生图供应商未配置 api-key: " + e.getProvider());
            }
            return e;
        }
        List<AiModelConfigEntity> all = listEnabledImageEntities();
        if (all.isEmpty()) {
            throw new BusinessException(400,
                    "暂无可用生图模型，请在「模型管理」添加 capability=image 的模型（需 invokeUrl + 供应商 api-key）");
        }
        return all.get(0);
    }

    /**
     * 取某供应商下第一个启用的 chat 模型 ID。
     */
    public String firstEnabledModelId(String provider) {
        return firstEnabledModelId(provider, CAP_CHAT);
    }

    public String firstEnabledModelId(String provider, String capability) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        String cap = normalizeCapability(capability, true);
        if (cap == null) {
            cap = CAP_CHAT;
        }
        LambdaQueryWrapper<AiModelConfigEntity> q = new LambdaQueryWrapper<AiModelConfigEntity>()
                .eq(AiModelConfigEntity::getProvider, provider)
                .eq(AiModelConfigEntity::getEnabled, 1)
                .orderByAsc(AiModelConfigEntity::getSortOrder)
                .last("LIMIT 1");
        applyCapabilityFilter(q, cap);
        AiModelConfigEntity e = aiModelConfigMapper.selectOne(q);
        return e != null ? e.getModelId() : null;
    }

    public List<Map<String, String>> listProviders() {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map.Entry<String, ProviderConfig> e : aiProperties.getAllAvailableProviders()) {
            Map<String, String> m = new HashMap<>();
            m.put("key", e.getKey());
            m.put("name", e.getValue().getName() != null ? e.getValue().getName() : e.getKey());
            result.add(m);
        }
        return result;
    }

    public AiModelConfigResponse getById(Long id) {
        return toResponse(require(id));
    }

    public AiModelConfigResponse create(AiModelConfigRequest request) {
        String provider = request.getProvider().trim();
        String modelId = request.getModelId().trim();
        String capability = normalizeCapability(request.getCapability(), true);
        if (capability == null) {
            capability = CAP_CHAT;
        }
        validateProvider(provider);
        validateImageFields(capability, request);
        assertUnique(provider, modelId, capability, null);

        AiModelConfigEntity entity = new AiModelConfigEntity();
        applyRequest(entity, request, capability);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.insert(entity);
        log.info("新增模型配置: capability={}, provider={}, modelId={}", capability, provider, modelId);
        return toResponse(entity);
    }

    public AiModelConfigResponse update(Long id, AiModelConfigRequest request) {
        AiModelConfigEntity entity = require(id);
        String provider = request.getProvider().trim();
        String modelId = request.getModelId().trim();
        String capability = normalizeCapability(request.getCapability(), true);
        if (capability == null) {
            capability = resolveCapability(entity);
        }
        validateProvider(provider);
        validateImageFields(capability, request);
        assertUnique(provider, modelId, capability, id);

        applyRequest(entity, request, capability);
        entity.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.updateById(entity);
        log.info("更新模型配置: id={}, capability={}, provider={}, modelId={}",
                id, capability, provider, modelId);
        return toResponse(entity);
    }

    public void delete(Long id) {
        require(id);
        aiModelConfigMapper.deleteById(id);
        log.info("删除模型配置: id={}", id);
    }

    private void applyRequest(AiModelConfigEntity entity, AiModelConfigRequest request, String capability) {
        entity.setProvider(request.getProvider().trim());
        entity.setModelId(request.getModelId().trim());
        entity.setModelName(request.getModelName().trim());
        entity.setCapability(capability);
        entity.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? 0 : 1);
        entity.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        entity.setRemark(request.getRemark() != null ? request.getRemark().trim() : null);
        if (CAP_IMAGE.equals(capability)) {
            entity.setInvokeUrl(request.getInvokeUrl() != null ? request.getInvokeUrl().trim() : null);
            entity.setDefaultSteps(request.getDefaultSteps() != null ? request.getDefaultSteps() : 4);
            entity.setMaxSteps(request.getMaxSteps() != null ? request.getMaxSteps() : 50);
            String protocol = request.getProtocol() != null ? request.getProtocol().trim() : null;
            if (protocol != null && protocol.isBlank()) {
                protocol = null;
            }
            entity.setProtocol(protocol);
        } else if (CAP_VIDEO_OMNI.equals(capability)) {
            entity.setInvokeUrl(null);
            entity.setDefaultSteps(null);
            entity.setMaxSteps(null);
            String protocol = request.getProtocol() != null ? request.getProtocol().trim() : "nvidia-omni-chat";
            if (protocol.isBlank()) {
                protocol = "nvidia-omni-chat";
            }
            entity.setProtocol(protocol);
        } else {
            entity.setInvokeUrl(null);
            entity.setDefaultSteps(null);
            entity.setMaxSteps(null);
            entity.setProtocol(null);
        }
    }

    private void validateImageFields(String capability, AiModelConfigRequest request) {
        if (!CAP_IMAGE.equals(capability)) {
            return;
        }
        if (request.getInvokeUrl() == null || request.getInvokeUrl().isBlank()) {
            throw new BusinessException(400, "文生图模型必须填写 invokeUrl（NVIDIA GenAI 完整地址）");
        }
        if (request.getDefaultSteps() != null && request.getDefaultSteps() < 1) {
            throw new BusinessException(400, "defaultSteps 须 ≥ 1");
        }
        if (request.getMaxSteps() != null && request.getMaxSteps() < 1) {
            throw new BusinessException(400, "maxSteps 须 ≥ 1");
        }
    }

    private void validateProvider(String provider) {
        ProviderConfig pc = aiProperties.getProvider(provider);
        if (pc == null) {
            throw new BusinessException(400, "未知供应商: " + provider + "，请先在 application.yml 的 ai.providers 中配置");
        }
    }

    private void assertUnique(String provider, String modelId, String capability, Long excludeId) {
        LambdaQueryWrapper<AiModelConfigEntity> q = new LambdaQueryWrapper<AiModelConfigEntity>()
                .eq(AiModelConfigEntity::getProvider, provider)
                .eq(AiModelConfigEntity::getModelId, modelId);
        applyCapabilityFilter(q, capability);
        if (excludeId != null) {
            q.ne(AiModelConfigEntity::getId, excludeId);
        }
        Long cnt = aiModelConfigMapper.selectCount(q);
        if (cnt != null && cnt > 0) {
            throw new BusinessException(400,
                    "该供应商下模型已存在: " + provider + " / " + modelId + " / " + capability);
        }
    }

    /**
     * capability 过滤：chat 兼容旧行（null/空/chat）。
     */
    private void applyCapabilityFilter(LambdaQueryWrapper<AiModelConfigEntity> q, String capability) {
        if (CAP_IMAGE.equals(capability)) {
            q.eq(AiModelConfigEntity::getCapability, CAP_IMAGE);
        } else if (CAP_VIDEO_OMNI.equals(capability)) {
            q.eq(AiModelConfigEntity::getCapability, CAP_VIDEO_OMNI);
        } else if (CAP_CHAT.equals(capability)) {
            q.and(w -> w.isNull(AiModelConfigEntity::getCapability)
                    .or().eq(AiModelConfigEntity::getCapability, "")
                    .or().eq(AiModelConfigEntity::getCapability, CAP_CHAT));
        }
    }

    private static String resolveCapability(AiModelConfigEntity e) {
        if (e.getCapability() == null || e.getCapability().isBlank()) {
            return CAP_CHAT;
        }
        return e.getCapability().trim().toLowerCase(Locale.ROOT);
    }

    /**
     * @param required  true 时非法值回退 chat；false 时 null 表示不过滤
     */
    private static String normalizeCapability(String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            return required ? CAP_CHAT : null;
        }
        String c = raw.trim().toLowerCase(Locale.ROOT);
        if (CAP_CHAT.equals(c) || CAP_IMAGE.equals(c) || CAP_VIDEO_OMNI.equals(c)) {
            return c;
        }
        if (required) {
            throw new BusinessException(400, "capability 仅支持 chat、image 或 video_omni");
        }
        return null;
    }

    private AiModelConfigEntity require(Long id) {
        AiModelConfigEntity entity = aiModelConfigMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(404, "模型配置不存在: " + id);
        }
        return entity;
    }

    private AiModelConfigResponse toResponse(AiModelConfigEntity e) {
        ProviderConfig pc = aiProperties.getProvider(e.getProvider());
        return AiModelConfigResponse.builder()
                .id(String.valueOf(e.getId()))
                .provider(e.getProvider())
                .providerName(pc != null && pc.getName() != null ? pc.getName() : e.getProvider())
                .modelId(e.getModelId())
                .modelName(e.getModelName())
                .capability(resolveCapability(e))
                .invokeUrl(e.getInvokeUrl())
                .defaultSteps(e.getDefaultSteps())
                .maxSteps(e.getMaxSteps())
                .protocol(e.getProtocol())
                .enabled(e.getEnabled() == null || e.getEnabled() == 1)
                .sortOrder(e.getSortOrder() != null ? e.getSortOrder() : 0)
                .remark(e.getRemark())
                .createdAt(formatTime(e.getCreatedAt()))
                .updatedAt(formatTime(e.getUpdatedAt()))
                .build();
    }

    private static String formatTime(LocalDateTime t) {
        return t == null ? null : t.format(DT_FMT);
    }
}
