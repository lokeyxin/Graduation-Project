package com.ragproject.ragserver.service;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.dto.response.LoginResponse;
import com.ragproject.ragserver.mapper.UserMapper;
import com.ragproject.ragserver.model.User;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthService {
    private static final int USERNAME_MIN_LENGTH = 3;
    private static final int USERNAME_MAX_LENGTH = 64;
    private static final int PASSWORD_MIN_LENGTH = 6;
    private static final int PASSWORD_MAX_LENGTH = 128;
    private static final int DISPLAY_NAME_MAX_LENGTH = 64;

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

    public void register(String username, String password, String displayName) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException("R001", "用户名不能为空");
        }
        if (!StringUtils.hasText(password)) {
            throw new BusinessException("R002", "密码不能为空");
        }

        String normalizedUsername = username.trim();
        String normalizedDisplayName = StringUtils.hasText(displayName) ? displayName.trim() : normalizedUsername;

        if (normalizedUsername.length() < USERNAME_MIN_LENGTH || normalizedUsername.length() > USERNAME_MAX_LENGTH) {
            throw new BusinessException("R003", "用户名长度需在3到64个字符之间");
        }
        if (password.length() < PASSWORD_MIN_LENGTH || password.length() > PASSWORD_MAX_LENGTH) {
            throw new BusinessException("R004", "密码长度需在6到128个字符之间");
        }
        if (normalizedDisplayName.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new BusinessException("R005", "显示名长度不能超过64个字符");
        }

        User existingUser = userMapper.findByUsername(normalizedUsername);
        if (existingUser != null) {
            throw new BusinessException("R006", "用户名已存在");
        }

        User user = new User();
        user.setUsername(normalizedUsername);
        user.setPasswordHash(password);
        user.setDisplayName(normalizedDisplayName);
        user.setStatus(1);

        try {
            int affectedRows = userMapper.insert(user);
            if (affectedRows != 1 || user.getUserId() == null) {
                throw new BusinessException("R007", "注册失败，请稍后重试");
            }
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("R006", "用户名已存在");
        }
    }
}
