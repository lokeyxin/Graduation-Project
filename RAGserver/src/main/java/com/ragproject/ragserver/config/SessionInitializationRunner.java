package com.ragproject.ragserver.config;

import com.ragproject.ragserver.mapper.UserMapper;
import com.ragproject.ragserver.model.User;
import com.ragproject.ragserver.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SessionInitializationRunner {
    private static final Logger log = LoggerFactory.getLogger(SessionInitializationRunner.class);

    private final UserMapper userMapper;
    private final SessionService sessionService;

    public SessionInitializationRunner(UserMapper userMapper, SessionService sessionService) {
        this.userMapper = userMapper;
        this.sessionService = sessionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeSessionsOnStartup() {
        List<User> users;
        try {
            users = userMapper.findAllActiveUsers();
        } catch (Exception ex) {
            log.info("Session initialization skipped: user table is not ready yet.");
            return;
        }

        if (users == null || users.isEmpty()) {
            return;
        }

        for (User user : users) {
            try {
                sessionService.initializeDefaultSessionForUser(user.getUserId());
            } catch (Exception ex) {
                log.error("Failed to initialize default session for userId={}", user.getUserId(), ex);
            }
        }
        log.info("Session initialization on startup finished, userCount={}", users.size());
    }
}
