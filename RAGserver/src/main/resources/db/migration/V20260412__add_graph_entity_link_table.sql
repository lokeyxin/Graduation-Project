-- 新增 MySQL 与 Neo4j 的实体连接表，用于 GraphRAG 回溯到知识项。
CREATE TABLE t_graph_entity_link (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '连接ID',
    document_id BIGINT NOT NULL COMMENT '文档ID',
    knowledge_id BIGINT NOT NULL COMMENT '知识项ID',
    graph_node_id BIGINT NOT NULL COMMENT 'Neo4j节点ID',
    entity_name VARCHAR(255) NOT NULL COMMENT '实体名',
    entity_type VARCHAR(64) NOT NULL COMMENT '实体类型',
    source_chunk_no INT NOT NULL DEFAULT 1 COMMENT '来源分片序号',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_graph_link_knowledge_node (knowledge_id, graph_node_id),
    KEY idx_graph_link_document_id (document_id),
    KEY idx_graph_link_knowledge_id (knowledge_id),
    CONSTRAINT fk_graph_link_document_id FOREIGN KEY (document_id) REFERENCES t_document(document_id),
    CONSTRAINT fk_graph_link_knowledge_id FOREIGN KEY (knowledge_id) REFERENCES t_knowledge_item(knowledge_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MySQL与Neo4j实体连接表';
