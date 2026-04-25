package com.ragproject.ragserver;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import com.ragproject.ragserver.service.agent.GraphRagAgentService;

@SpringBootTest
@ActiveProfiles("test")
class RAGserverApplicationTests {

    @MockBean
    private ChatClient.Builder chatClientBuilder;

    @MockBean
    private VectorStore vectorStore;

    @MockBean
    private GraphRagAgentService graphRagAgentService;

    @Test
    void contextLoads() {
    }

}
