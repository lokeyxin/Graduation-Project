package com.ragproject.ragserver.dto.response;

public class LoginResponse {
    private String token;
    private Long userId;
    private String username;
    private String displayName;

    public LoginResponse(String token, Long userId, String username, String displayName) {
        this.token = token;
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
    }

    public String getToken() {
        return token;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }
}
