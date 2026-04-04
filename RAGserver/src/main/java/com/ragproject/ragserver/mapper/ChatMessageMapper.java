package com.ragproject.ragserver.mapper;

import com.ragproject.ragserver.model.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatMessageMapper {
    int insert(ChatMessage chatMessage);

    List<ChatMessage> findBySessionId(@Param("sessionId") Long sessionId);
}
