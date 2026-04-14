package com.ragproject.ragserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragproject.ragserver.dto.graph.ExtractedEntity;
import com.ragproject.ragserver.dto.graph.GraphExtractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EntityExtractionService {
    private static final Logger log = LoggerFactory.getLogger(EntityExtractionService.class);

    private static final String EXTRACTION_SYSTEM_PROMPT = """
            你是知识图谱构建助手。请从输入文本中抽取实体和关系，并严格返回 JSON。
        实体类型判定规则：
        1) Person: 具体人物姓名、称谓明确的人（如“张三”“李教授”）。
        2) Organization: 公司、学校、政府机构、医院、法院、协会等组织实体。
        3) Location: 国家、省市区、道路、园区、具体地理地点。
        4) Event: 会议、发布会、事故、战争、比赛、展会等可发生/已发生事件。
        5) Concept: 仅用于抽象概念、理论、技术术语（不得把具体人名/机构/地点误归为Concept）。
        关系类型约束：RELATED_TO, MENTIONS, WORKS_FOR, LOCATED_IN, PART_OF。
        输出约束：
        1) 仅输出如下 JSON 结构，不要输出任何额外文本：
            {
              \"entities\": [{\"name\": \"...\", \"type\": \"Person|Organization|Location|Concept|Event\"}],
              \"relations\": [{\"from\": \"...\", \"to\": \"...\", \"type\": \"RELATED_TO|MENTIONS|WORKS_FOR|LOCATED_IN|PART_OF\", \"evidence\": \"...\"}]
            }
        2) 若未识别到实体或关系，返回空数组。
        3) 关系中的 from/to 必须引用 entities 中出现过的 name。
        4) 不允许把大多数实体默认标成Concept，需按文本证据分类。
            """;

    private static final String RETRY_SYSTEM_PROMPT = """
        你上一次的实体类型分类存在“Concept偏置”。
        请重新分类，要求：
        1) Person/Organization/Location/Event优先，只有抽象术语才可标Concept。
        2) 若实体是具体名称（人名、机构名、地名、事件名），禁止标Concept。
        3) 仅输出JSON，不要解释。
        """;

    private final ChatClient entityExtractionChatClient;
    private final ObjectMapper objectMapper;

    @Value("${app.graph.extraction.enabled:true}")
    private boolean graphExtractionEnabled;

    @Value("${app.graph.extraction.concept-bias-threshold:0.8}")
    private double conceptBiasThreshold;

    @Value("${app.graph.extraction.retry-on-concept-bias:true}")
    private boolean retryOnConceptBias;

    public EntityExtractionService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        // 单独构建实体抽取客户端，避免和问答链路共享会话上下文与后续参数策略。
        this.entityExtractionChatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public GraphExtractionResult extract(String documentName, String chunkText) {
        if (!graphExtractionEnabled || !StringUtils.hasText(chunkText)) {
            return new GraphExtractionResult();
        }

        String userPrompt = "文档名: " + safe(documentName) + "\\n文本: " + chunkText;
        String content = entityExtractionChatClient.prompt()
                .system(EXTRACTION_SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        if (!StringUtils.hasText(content)) {
            return new GraphExtractionResult();
        }

        try {
            String normalizedJson = normalizeJson(content);
            GraphExtractionResult parsed = objectMapper.readValue(normalizedJson, GraphExtractionResult.class);
            if (retryOnConceptBias && isConceptBias(parsed)) {
                String retryContent = entityExtractionChatClient.prompt()
                        .system(RETRY_SYSTEM_PROMPT)
                        .user(userPrompt)
                        .call()
                        .content();
                if (StringUtils.hasText(retryContent)) {
                    String retryJson = normalizeJson(retryContent);
                    GraphExtractionResult retried = objectMapper.readValue(retryJson, GraphExtractionResult.class);
                    if (!isConceptBias(retried)) {
                        return retried;
                    }
                }
            }
            return parsed;
        } catch (Exception ex) {
            log.warn("Entity extraction response parsing failed. doc={}, reason={}", safe(documentName), ex.getMessage());
            return new GraphExtractionResult();
        }
    }

    private boolean isConceptBias(GraphExtractionResult result) {
        if (result == null || result.getEntities() == null || result.getEntities().isEmpty()) {
            return false;
        }

        int total = 0;
        int conceptCount = 0;
        for (ExtractedEntity entity : result.getEntities()) {
            if (entity == null || !StringUtils.hasText(entity.getName())) {
                continue;
            }
            total++;
            if ("CONCEPT".equalsIgnoreCase(entity.getType())) {
                conceptCount++;
            }
        }
        if (total == 0) {
            return false;
        }
        double ratio = (double) conceptCount / total;
        return ratio >= Math.max(0.1d, Math.min(conceptBiasThreshold, 1.0d));
    }

    private String normalizeJson(String raw) {
        String value = raw.trim();
        if (value.startsWith("```")) {
            int firstBreak = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) {
                value = value.substring(firstBreak + 1, lastFence).trim();
            }
        }
        return value;
    }

    private String safe(String input) {
        if (!StringUtils.hasText(input)) {
            return "unknown";
        }
        return input.trim();
    }
}
