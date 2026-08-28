package com.attendance.api.config;

import com.attendance.api.domain.User;
import com.attendance.api.domain.enums.Role;
import com.attendance.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the platform super admin on first start so the API is reachable at all —
 * without it there is no account that can provision the first organization.
 * Runs only when no platform user exists, and can be turned off with
 * {@code app.seed.enabled=false}.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class BootstrapSeeder {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    @Bean
    public ApplicationRunner seedSuperAdmin() {
        return args -> createSuperAdminIfAbsent();
    }

    @Transactional
    void createSuperAdminIfAbsent() {
        AppProperties.Seed seed = appProperties.getSeed();
        if (!seed.isEnabled()) {
            log.debug("Bootstrap seeding disabled");
            return;
        }

        String email = seed.getSuperAdminEmail().trim().toLowerCase();
        if (userRepository.findPlatformUserByEmail(email).isPresent()) {
            log.debug("Platform super admin {} already present", email);
            return;
        }

        userRepository.save(User.builder()
                .organization(null)
                .email(email)
                .passwordHash(passwordEncoder.encode(seed.getSuperAdminPassword()))
                .firstName("Platform")
                .lastName("Admin")
                .role(Role.SUPER_ADMIN)
                .active(true)
                .build());

        log.warn("""

                ============================================================
                 Bootstrap super admin created
                   email:    {}
                   password: {}
                 Change this password immediately, and set app.seed.enabled=false
                 (or APP_SEED_ENABLED=false) outside development.
                ============================================================
                """, email, seed.getSuperAdminPassword());
    }
}
