package com.ragproject.ragserver.repository;

import com.ragproject.ragserver.model.graph.GraphEntityNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.Optional;

public interface GraphEntityNodeRepository extends Neo4jRepository<GraphEntityNode, Long> {
        @Query("""
                        MATCH (n:Entity)
                        WHERE coalesce(n.normalizedName, toLower(n.name)) = $normalizedName
                            AND n.type = $type
                            AND n.documentId = $documentId
                        RETURN n
                        LIMIT 1
                        """)
        Optional<GraphEntityNode> findFirstByNormalizedNameAndTypeAndDocumentId(String normalizedName, String type, Long documentId);

    @Query("MATCH (n:Entity {documentId: $documentId}) DETACH DELETE n")
    void deleteAllByDocumentId(Long documentId);
}
