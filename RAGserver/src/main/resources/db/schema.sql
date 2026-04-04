DROP TABLE IF EXISTS t_chat_message;
DROP TABLE IF EXISTS t_chat_session;
DROP TABLE IF EXISTS t_knowledge_item;
DROP TABLE IF EXISTS t_document;
DROP TABLE IF EXISTS t_user;

CREATE TABLE t_user (
    user_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(64) NOT NULL UNIQUE COMMENT '用户名',
    password_hash VARCHAR(128) NOT NULL COMMENT '密码哈希(开发环境可存明文)',
    display_name VARCHAR(64) NOT NULL COMMENT '显示名',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1启用0禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

CREATE TABLE t_document (
    document_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文档ID',
    user_id BIGINT NOT NULL COMMENT '上传用户ID',
    document_name VARCHAR(255) NOT NULL COMMENT '文档名',
    source_path VARCHAR(512) NOT NULL COMMENT '来源路径',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1有效0无效',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_document_user_id (user_id),
    CONSTRAINT fk_document_user_id FOREIGN KEY (user_id) REFERENCES t_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档元数据表';

CREATE TABLE t_knowledge_item (
    knowledge_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '知识项ID',
    question VARCHAR(512) NOT NULL COMMENT '标准问题',
    answer TEXT NOT NULL COMMENT '标准答案',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1有效0无效',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_knowledge_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识问答表';

CREATE TABLE t_chat_session (
    session_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    title VARCHAR(128) NOT NULL COMMENT '会话标题',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1有效0删除',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_session_user_id (user_id),
    CONSTRAINT fk_session_user_id FOREIGN KEY (user_id) REFERENCES t_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天会话表';

CREATE TABLE t_chat_message (
    message_id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '消息ID',
    session_id BIGINT NOT NULL COMMENT '会话ID',
    role VARCHAR(20) NOT NULL COMMENT '角色:user/assistant/system',
    content TEXT NOT NULL COMMENT '消息内容',
    token_count INT DEFAULT 0 COMMENT 'token数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_message_session_id (session_id),
    CONSTRAINT fk_message_session_id FOREIGN KEY (session_id) REFERENCES t_chat_session(session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聊天消息表';
