package com.ragproject.ragserver.service;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.dto.response.LoginResponse;
import com.ragproject.ragserver.mapper.UserMapper;
import com.ragproject.ragserver.model.User;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthService {
    private final UserMapper userMapper;
    private final JwtService jwtService;

    public AuthService(UserMapper userMapper, JwtService jwtService) {
        this.userMapper = userMapper;
        this.jwtService = jwtService;
    }

    public LoginResponse login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BusinessException("A001", "用户名或密码不能为空");
        }

        User user = userMapper.findByUsername(username.trim());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException("A002", "用户不存在或已禁用");
        }

        // 开发期兼容: 数据库可存明文，后续可以迁移为 BCrypt 哈希校验。
        if (!password.equals(user.getPasswordHash())) {
            throw new BusinessException("A003", "用户名或密码错误");
        }

        String token = jwtService.generateToken(user.getUserId(), user.getUsername());
        return new LoginResponse(token, user.getUserId(), user.getUsername(), user.getDisplayName());
    }
}
