package com.ragproject.ragserver.mapper;

import com.ragproject.ragserver.model.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentMapper {
    int insert(Document document);

    Document findById(@Param("documentId") Long documentId);

    Document findByUserIdAndDocumentName(@Param("userId") Long userId, @Param("documentName") String documentName);

    int updateStatusById(@Param("documentId") Long documentId, @Param("status") Integer status);

    int updateForReplace(@Param("documentId") Long documentId,
                         @Param("sourcePath") String sourcePath,
                         @Param("status") Integer status);

    int deleteById(@Param("documentId") Long documentId);

    List<Document> findByUserId(@Param("userId") Long userId);
}
