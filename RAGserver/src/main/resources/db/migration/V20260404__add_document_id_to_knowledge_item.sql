-- 为已上线库补充 document_id 关联字段。
-- 说明：
-- 1) 该脚本用于“历史数据迁移”，不影响新库通过 schema.sql 初始化；
-- 2) 旧知识项无法准确追溯来源文档时，先回填到最小 document_id（若存在）；
-- 3) 保持 document_id 可空，避免历史脏数据导致迁移失败。

ALTER TABLE t_knowledge_item
    ADD COLUMN document_id BIGINT NULL COMMENT '关联文档ID' AFTER knowledge_id;

UPDATE t_knowledge_item
SET document_id = (SELECT MIN(document_id) FROM t_document)
WHERE document_id IS NULL;

ALTER TABLE t_knowledge_item
    ADD INDEX idx_knowledge_document_id (document_id);

ALTER TABLE t_knowledge_item
    ADD CONSTRAINT fk_knowledge_document_id
        FOREIGN KEY (document_id) REFERENCES t_document (document_id);
