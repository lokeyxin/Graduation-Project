-- 增加同一用户下文档名唯一约束，防止并发上传导致重复记录。
ALTER TABLE t_document
    ADD CONSTRAINT uk_document_user_name UNIQUE (user_id, document_name);
