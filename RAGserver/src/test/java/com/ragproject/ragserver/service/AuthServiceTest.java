package com.ragproject.ragserver.service;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.dto.response.LoginResponse;
import com.ragproject.ragserver.mapper.UserMapper;
import com.ragproject.ragserver.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    @Test
    void loginShouldReturnTokenWhenCredentialsValid() {
        User user = new User();
        user.setUserId(1L);
        user.setUsername("demo01");
        user.setPasswordHash("123456");
        user.setDisplayName("演示用户A");
        user.setStatus(1);

        when(userMapper.findByUsername("demo01")).thenReturn(user);
        when(jwtService.generateToken(1L, "demo01")).thenReturn("mock-token");

        LoginResponse response = authService.login("demo01", "123456");

        assertEquals("mock-token", response.getToken());
        assertEquals(1L, response.getUserId());
        assertEquals("demo01", response.getUsername());
        assertEquals("演示用户A", response.getDisplayName());
    }

    @Test
    void registerShouldThrowWhenUsernameExists() {
        User existing = new User();
        existing.setUserId(10L);
        existing.setUsername("taken");

        when(userMapper.findByUsername("taken")).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register("taken", "123456", ""));

        assertEquals("R006", ex.getCode());
    }

    @Test
    void registerShouldInsertUserWhenInputValid() {
        when(userMapper.findByUsername("new_user")).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setUserId(100L);
            return 1;
        });

        authService.register("  new_user  ", "123456", "");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());

        User inserted = captor.getValue();
        assertNotNull(inserted);
        assertEquals("new_user", inserted.getUsername());
        assertEquals("123456", inserted.getPasswordHash());
        assertEquals("new_user", inserted.getDisplayName());
        assertEquals(1, inserted.getStatus());
    }
}