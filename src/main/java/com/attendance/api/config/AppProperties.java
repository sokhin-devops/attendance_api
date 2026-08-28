package com.attendance.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Binds the {@code app.*} block of application.yml. */
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Cors cors = new Cors();
    private Seed seed = new Seed();
    private Notifications notifications = new Notifications();
    private Fcm fcm = new Fcm();

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long accessTokenMinutes = 15;
        private long refreshTokenDays = 7;
        private String issuer = "attendance-api";
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:4200");
    }

    @Getter
    @Setter
    public static class Seed {
        private boolean enabled = true;
        private String superAdminEmail = "superadmin@attendance.local";
        private String superAdminPassword = "SuperAdmin@123";
    }

    @Getter
    @Setter
    public static class Notifications {
        private int retentionDays = 90;
    }

    @Getter
    @Setter
    public static class Fcm {
        private boolean enabled = false;
    }
}
