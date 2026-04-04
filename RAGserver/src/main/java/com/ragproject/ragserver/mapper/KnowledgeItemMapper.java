package com.ragproject.ragserver.mapper;

import com.ragproject.ragserver.model.KnowledgeItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KnowledgeItemMapper {
    List<KnowledgeItem> findActiveItems();

    int insertBatch(@Param("items") List<KnowledgeItem> items);
}
