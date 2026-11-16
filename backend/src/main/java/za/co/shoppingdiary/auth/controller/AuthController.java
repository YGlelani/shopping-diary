package za.co.shoppingdiary.auth.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import za.co.shoppingdiary.auth.UserPrincipal;
import za.co.shoppingdiary.auth.dto.*;
import za.co.shoppingdiary.auth.service.AuthService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return auth.register(req);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req);
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@org.springframework.security.core.annotation.AuthenticationPrincipal UserPrincipal me) {
        if (me == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        return ResponseEntity.ok(Map.of(
                "userId", me.getId(),
                "email", me.getUsername(),
                "role", me.getRole().name()));
    }
}