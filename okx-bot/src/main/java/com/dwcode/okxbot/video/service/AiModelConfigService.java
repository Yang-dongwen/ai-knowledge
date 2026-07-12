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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM 模型配置服务（数据库驱动，替代 yml models 列表）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelConfigService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiModelConfigMapper aiModelConfigMapper;
    private final AiProperties aiProperties;

    /**
     * 管理列表：全部模型（含禁用），按 sortOrder、id。
     */
    public List<AiModelConfigResponse> listAll() {
        List<AiModelConfigEntity> list = aiModelConfigMapper.selectList(
                new LambdaQueryWrapper<AiModelConfigEntity>()
                        .orderByAsc(AiModelConfigEntity::getSortOrder)
                        .orderByDesc(AiModelConfigEntity::getCreatedAt)
        );
        return list.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 任务选择用：仅启用的模型，且对应供应商在 yml 中有 api-key。
     * 结构与原先 listLlmModels 一致：[{key,name,models:[{id,name}]}]
     */
    public List<Map<String, Object>> listEnabledGroupedByProvider() {
        List<AiModelConfigEntity> list = aiModelConfigMapper.selectList(
                new LambdaQueryWrapper<AiModelConfigEntity>()
                        .eq(AiModelConfigEntity::getEnabled, 1)
                        .orderByAsc(AiModelConfigEntity::getSortOrder)
                        .orderByDesc(AiModelConfigEntity::getCreatedAt)
        );

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
            List<Map<String, String>> models = new ArrayList<>();
            for (AiModelConfigEntity m : entry.getValue()) {
                Map<String, String> mm = new HashMap<>();
                mm.put("id", m.getModelId());
                mm.put("name", m.getModelName() != null ? m.getModelName() : m.getModelId());
                models.add(mm);
            }
            providerMap.put("models", models);
            result.add(providerMap);
        }
        return result;
    }

    /**
     * 取某供应商下第一个启用的模型 ID（用于默认值）。
     */
    public String firstEnabledModelId(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        AiModelConfigEntity e = aiModelConfigMapper.selectOne(
                new LambdaQueryWrapper<AiModelConfigEntity>()
                        .eq(AiModelConfigEntity::getProvider, provider)
                        .eq(AiModelConfigEntity::getEnabled, 1)
                        .orderByAsc(AiModelConfigEntity::getSortOrder)
                        .last("LIMIT 1")
        );
        return e != null ? e.getModelId() : null;
    }

    /**
     * 有 api-key 的供应商列表（管理页下拉用）。
     */
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
        validateProvider(provider);
        assertUnique(provider, modelId, null);

        AiModelConfigEntity entity = new AiModelConfigEntity();
        applyRequest(entity, request);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.insert(entity);
        log.info("新增 LLM 模型配置: provider={}, modelId={}", provider, modelId);
        return toResponse(entity);
    }

    public AiModelConfigResponse update(Long id, AiModelConfigRequest request) {
        AiModelConfigEntity entity = require(id);
        String provider = request.getProvider().trim();
        String modelId = request.getModelId().trim();
        validateProvider(provider);
        assertUnique(provider, modelId, id);

        applyRequest(entity, request);
        entity.setUpdatedAt(LocalDateTime.now());
        aiModelConfigMapper.updateById(entity);
        log.info("更新 LLM 模型配置: id={}, provider={}, modelId={}", id, provider, modelId);
        return toResponse(entity);
    }

    public void delete(Long id) {
        require(id);
        aiModelConfigMapper.deleteById(id);
        log.info("删除 LLM 模型配置: id={}", id);
    }

    private void applyRequest(AiModelConfigEntity entity, AiModelConfigRequest request) {
        entity.setProvider(request.getProvider().trim());
        entity.setModelId(request.getModelId().trim());
        entity.setModelName(request.getModelName().trim());
        entity.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? 0 : 1);
        entity.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0);
        entity.setRemark(request.getRemark() != null ? request.getRemark().trim() : null);
    }

    private void validateProvider(String provider) {
        ProviderConfig pc = aiProperties.getProvider(provider);
        if (pc == null) {
            throw new BusinessException(400, "未知供应商: " + provider + "，请先在 application.yml 的 ai.providers 中配置");
        }
    }

    private void assertUnique(String provider, String modelId, Long excludeId) {
        LambdaQueryWrapper<AiModelConfigEntity> q = new LambdaQueryWrapper<AiModelConfigEntity>()
                .eq(AiModelConfigEntity::getProvider, provider)
                .eq(AiModelConfigEntity::getModelId, modelId);
        if (excludeId != null) {
            q.ne(AiModelConfigEntity::getId, excludeId);
        }
        Long cnt = aiModelConfigMapper.selectCount(q);
        if (cnt != null && cnt > 0) {
            throw new BusinessException(400, "该供应商下模型 ID 已存在: " + provider + " / " + modelId);
        }
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
