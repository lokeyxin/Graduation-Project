package com.ragproject.ragserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
            约束如下：
            1) 仅允许实体类型: Person, Organization, Location, Concept, Event
            2) 仅允许关系类型: RELATED_TO, MENTIONS, WORKS_FOR, LOCATED_IN, PART_OF
            3) 仅输出如下 JSON 结构，不要输出任何额外文本：
            {
              \"entities\": [{\"name\": \"...\", \"type\": \"Person|Organization|Location|Concept|Event\"}],
              \"relations\": [{\"from\": \"...\", \"to\": \"...\", \"type\": \"RELATED_TO|MENTIONS|WORKS_FOR|LOCATED_IN|PART_OF\", \"evidence\": \"...\"}]
            }
            4) 若未识别到实体或关系，返回空数组。
            5) 关系中的 from/to 必须引用 entities 中出现过的 name。
            """;

    private final ChatClient entityExtractionChatClient;
    private final ObjectMapper objectMapper;

    @Value("${app.graph.extraction.enabled:true}")
    private boolean graphExtractionEnabled;

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
            return objectMapper.readValue(normalizedJson, GraphExtractionResult.class);
        } catch (Exception ex) {
            log.warn("Entity extraction response parsing failed. doc={}, reason={}", safe(documentName), ex.getMessage());
            return new GraphExtractionResult();
        }
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
