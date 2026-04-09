
package com.ragproject.ragserver.service;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.dto.response.MessageResponse;
import com.ragproject.ragserver.dto.response.SessionResponse;
import com.ragproject.ragserver.mapper.ChatMessageMapper;
import com.ragproject.ragserver.mapper.ChatSessionMapper;
import com.ragproject.ragserver.model.ChatMessage;
import com.ragproject.ragserver.model.ChatSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class SessionService {
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_DELETED = 0;
    private static final String DEFAULT_SESSION_TITLE = "默认会话";
    private static final String GREETING_MESSAGE = "我是一个AI智能客服，您有什么问题？";

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    public SessionService(ChatSessionMapper chatSessionMapper, ChatMessageMapper chatMessageMapper) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMessageMapper = chatMessageMapper;
    }

    public SessionResponse createSession(Long userId, String title) {
        return createSessionInternal(userId,
                StringUtils.hasText(title) ? title.trim() : "新对话",
                false);
    }

    @Transactional
    public void deleteSession(Long userId, Long sessionId) {
        ensureSessionOwner(userId, sessionId);

        chatMessageMapper.deleteBySessionId(sessionId);
        chatSessionMapper.updateStatusBySessionIdAndUserId(sessionId, userId, STATUS_DELETED);

        if (chatSessionMapper.countActiveByUserId(userId) == 0) {
            createSessionInternal(userId, DEFAULT_SESSION_TITLE, true);
        }
    }

    @Transactional
    public void initializeDefaultSessionForUser(Long userId) {
        chatSessionMapper.updateStatusByUserId(userId, STATUS_DELETED);
        createSessionInternal(userId, DEFAULT_SESSION_TITLE, true);
    }

    private SessionResponse createSessionInternal(Long userId, String title, boolean withGreeting) {
        ChatSession chatSession = new ChatSession();
        chatSession.setUserId(userId);
        chatSession.setTitle(title);
        chatSession.setStatus(STATUS_ACTIVE);
        chatSessionMapper.insert(chatSession);

        if (withGreeting) {
            ChatMessage assistantMessage = new ChatMessage();
            assistantMessage.setSessionId(chatSession.getSessionId());
            assistantMessage.setRole("assistant");
            assistantMessage.setContent(GREETING_MESSAGE);
            assistantMessage.setTokenCount(0);
            chatMessageMapper.insert(assistantMessage);
        }

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
