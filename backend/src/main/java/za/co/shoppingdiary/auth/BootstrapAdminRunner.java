package za.co.shoppingdiary.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.co.shoppingdiary.user.Role;
import za.co.shoppingdiary.user.User;
import za.co.shoppingdiary.user.UserRepository;

@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapAdminRunner implements ApplicationRunner {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    @Value("${app.bootstrap.admin-email:}")    private String email;
    @Value("${app.bootstrap.admin-password:}") private String password;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        if (users.existsByEmail(email)) {
            log.info("Bootstrap admin '{}' already exists — skipping", email);
            return;
        }
        User admin = new User();
        admin.setEmail(email);
        admin.setPasswordHash(encoder.encode(password));
        admin.setDisplayName("Administrator");
        admin.setRole(Role.ADMIN);
        admin.setEmailVerified(true);
        users.save(admin);
        log.info("Bootstrap admin '{}' created", email);
    }
}