
package com.ragproject.ragserver.service;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.dto.response.MessageResponse;
import com.ragproject.ragserver.dto.response.SessionResponse;
import com.ragproject.ragserver.mapper.ChatMessageMapper;
import com.ragproject.ragserver.mapper.ChatSessionMapper;
import com.ragproject.ragserver.model.ChatMessage;
import com.ragproject.ragserver.model.ChatSession;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class SessionService {
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    public SessionService(ChatSessionMapper chatSessionMapper, ChatMessageMapper chatMessageMapper) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
    }

    public SessionResponse createSession(Long userId, String title) {
        ChatSession chatSession = new ChatSession();
        chatSession.setUserId(userId);
        chatSession.setTitle(StringUtils.hasText(title) ? title.trim() : "新对话");
        chatSession.setStatus(1);
        chatSessionMapper.insert(chatSession);
        return new SessionResponse(chatSession.getSessionId(), chatSession.getTitle(), chatSession.getCreatedAt());
    }

    public List<SessionResponse> listSessions(Long userId) {
        return chatSessionMapper.findByUserId(userId).stream()
                .map(item -> new SessionResponse(item.getSessionId(), item.getTitle(), item.getCreatedAt()))
                .toList();
    }

    public List<MessageResponse> listMessages(Long userId, Long sessionId) {
        ensureSessionOwner(userId, sessionId);
        return chatMessageMapper.findBySessionId(sessionId).stream()
                .map(item -> new MessageResponse(item.getMessageId(), item.getSessionId(), item.getRole(), item.getContent(), item.getCreatedAt()))
                .toList();
    }

    public void ensureSessionOwner(Long userId, Long sessionId) {
        ChatSession session = chatSessionMapper.findBySessionIdAndUserId(sessionId, userId);
        if (session == null) {
            throw new BusinessException("S404", "会话不存在");
        }
    }
}
