package com.ragproject.ragserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 启动时对历史数据库做“幂等结构补丁”。
 *
 * 背景：
 * - 新版本代码已经依赖 t_knowledge_item.document_id；
 * - 旧环境可能还没有该列，导致应用启动后查询报错。
 *
 * 设计原则：
 * 1) 只在缺失时补，重复启动不会破坏已有结构；
 * 2) 在应用完成启动流程前执行（CommandLineRunner + @Order）；
 * 3) 补丁失败仅记录错误并抛出异常，避免系统带病运行。
 */
@Component
@Order(0)
public class DatabaseSchemaPatchRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaPatchRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaPatchRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        if (!isMySqlDatabase()) {
            log.info("Schema patch skipped: current database is not MySQL.");
            return;
        }

        ensureDocumentIdColumn();
        ensureDocumentIdIndex();
        backfillDocumentIdForLegacyRows();
        ensureDocumentIdForeignKey();
    }

    private boolean isMySqlDatabase() {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            return false;
        }

        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            return productName != null && productName.toLowerCase().contains("mysql");
        } catch (SQLException e) {
            log.warn("Failed to detect database product, skip schema patch for safety.", e);
            return false;
        }
    }

    private void ensureDocumentIdColumn() {
        if (columnExists("t_knowledge_item", "document_id")) {
            return;
        }

        jdbcTemplate.execute("""
                ALTER TABLE t_knowledge_item
                ADD COLUMN document_id BIGINT NULL COMMENT '关联文档ID' AFTER knowledge_id
                """);
        log.info("Schema patch applied: added t_knowledge_item.document_id");
    }

    private void ensureDocumentIdIndex() {
        if (indexExists("t_knowledge_item", "idx_knowledge_document_id")) {
            return;
        }

        jdbcTemplate.execute("""
                ALTER TABLE t_knowledge_item
                ADD INDEX idx_knowledge_document_id (document_id)
                """);
        log.info("Schema patch applied: added index idx_knowledge_document_id");
    }

    private void backfillDocumentIdForLegacyRows() {
        if (!columnExists("t_knowledge_item", "document_id")) {
            return;
        }

        // 历史知识项没有明确文档来源时，先回填到最小 document_id，避免全为空影响关联查询。
        jdbcTemplate.execute("""
                UPDATE t_knowledge_item
                SET document_id = (SELECT MIN(document_id) FROM t_document)
                WHERE document_id IS NULL
                """);
    }

    private void ensureDocumentIdForeignKey() {
        if (foreignKeyExists("t_knowledge_item", "fk_knowledge_document_id")) {
            return;
        }

        jdbcTemplate.execute("""
                ALTER TABLE t_knowledge_item
                ADD CONSTRAINT fk_knowledge_document_id
                FOREIGN KEY (document_id) REFERENCES t_document(document_id)
                """);
        log.info("Schema patch applied: added fk_knowledge_document_id");
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND INDEX_NAME = ?
                """, Integer.class, tableName, indexName);
        return count != null && count > 0;
    }

    private boolean foreignKeyExists(String tableName, String constraintName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND CONSTRAINT_NAME = ?
                  AND CONSTRAINT_TYPE = 'FOREIGN KEY'
                """, Integer.class, tableName, constraintName);
        return count != null && count > 0;
    }
}
