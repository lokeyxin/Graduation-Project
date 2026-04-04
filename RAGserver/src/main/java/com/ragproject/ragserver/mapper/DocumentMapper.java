package com.ragproject.ragserver.mapper;

import com.ragproject.ragserver.model.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentMapper {
    int insert(Document document);

    int updateStatusById(@Param("documentId") Long documentId, @Param("status") Integer status);

    List<Document> findByUserId(@Param("userId") Long userId);
}
