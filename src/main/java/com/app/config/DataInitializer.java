package com.app.config;

import com.app.entity.Role;
import com.app.entity.User;
import com.app.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        String adminEmail = "admin@authapp.local";

        if (userRepository.existsByEmail(adminEmail)) {
            log.info("Default admin user already exists, skipping seed");
            return;
        }

        User admin = User.builder()
                .fullName("System Admin")
                .email(adminEmail)
                .password(passwordEncoder.encode("Admin@123456"))
                .role(Role.ROLE_ADMIN)
                .build();

        userRepository.save(admin);
        log.info("Default admin user created: email={}, password=Admin@123456", adminEmail);
    }
}
