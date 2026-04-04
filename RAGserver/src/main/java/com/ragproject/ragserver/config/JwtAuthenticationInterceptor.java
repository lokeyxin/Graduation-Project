package com.ragproject.ragserver.config;

import com.ragproject.ragserver.common.BusinessException;
import com.ragproject.ragserver.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtAuthenticationInterceptor implements HandlerInterceptor {
    private final JwtService jwtService;

    public JwtAuthenticationInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException("U401", "未登录或令牌缺失");
        }

        String token = authHeader.substring(7);
        Claims claims;
        try {
            claims = jwtService.parseToken(token);
        } catch (Exception ex) {
            throw new BusinessException("U401", "令牌无效或已过期");
        }

        Long userId = Long.parseLong(claims.getSubject());
        request.setAttribute("currentUserId", userId);
        return true;
    }
}
