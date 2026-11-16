package za.co.shoppingdiary.auth.dto;

public class AuthResponse {

    private String token;
    private String tokenType = "Bearer";
    private long expiresInSeconds;
    private Long userId;
    private String email;
    private String displayName;
    private String role;

    public AuthResponse(String token, long expiresInSeconds, Long userId,
                        String email, String displayName, String role) {
        this.token = token;
        this.expiresInSeconds = expiresInSeconds;
        this.userId = userId;
        this.email = email;
        this.displayName = displayName;
        this.role = role;
    }

    public String getToken() { return token; }
    public String getTokenType() { return tokenType; }
    public long getExpiresInSeconds() { return expiresInSeconds; }
    public Long getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getRole() { return role; }
}