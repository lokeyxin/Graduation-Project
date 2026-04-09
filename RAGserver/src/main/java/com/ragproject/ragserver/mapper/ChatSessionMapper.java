package com.ragproject.ragserver.mapper;

import com.ragproject.ragserver.model.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatSessionMapper {
    int insert(ChatSession chatSession);

    List<ChatSession> findByUserId(@Param("userId") Long userId);

    ChatSession findBySessionIdAndUserId(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    int updateStatusBySessionIdAndUserId(@Param("sessionId") Long sessionId,
                                         @Param("userId") Long userId,
                                         @Param("status") Integer status);

    int updateStatusByUserId(@Param("userId") Long userId, @Param("status") Integer status);

    int countActiveByUserId(@Param("userId") Long userId);
}
