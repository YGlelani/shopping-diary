package za.co.shoppingdiary.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.shoppingdiary.auth.dto.*;
import za.co.shoppingdiary.user.Role;
import za.co.shoppingdiary.user.User;
import za.co.shoppingdiary.user.UserRepository;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        if (users.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already registered");
        }
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(req.getPassword()));
        u.setDisplayName(req.getDisplayName().trim());
        u.setRole(Role.USER);
        users.save(u);
        return issueResponse(u);
    }

    public AuthResponse login(LoginRequest req) {
        String email = req.getEmail().trim().toLowerCase();
        User u = users.findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        if (!encoder.matches(req.getPassword(), u.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }
        return issueResponse(u);
    }

    private AuthResponse issueResponse(User u) {
        String token = jwt.issue(u);
        // expirationMs is fixed config; compute from env is cleaner via a second injection,
        // but for simplicity just expose 24h from config
        return new AuthResponse(token, 86_400L,
                u.getId(), u.getEmail(), u.getDisplayName(), u.getRole().name());
    }
}