package com.ragproject.ragserver.mapper;

import com.ragproject.ragserver.model.GraphEntityLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface GraphEntityLinkMapper {
    int insertBatch(@Param("items") List<GraphEntityLink> items);

    int deleteByDocumentId(@Param("documentId") Long documentId);
}
