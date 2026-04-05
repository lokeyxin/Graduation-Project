package com.ragproject.ragserver;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@MapperScan("com.ragproject.ragserver.mapper")
public class RAGserverApplication {

    public static void main(String[] args) {
        SpringApplication.run(RAGserverApplication.class, args);
    }

}
