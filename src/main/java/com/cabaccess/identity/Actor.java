package com.cabaccess.identity;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Optional;

public record Actor(String subject, boolean platformAdmin) {
    public static Actor from(Jwt jwt) {
        return new Actor(jwt.getSubject(), Optional.ofNullable(jwt.getClaimAsStringList("roles")).orElse(List.of()).contains("PLATFORM_ADMIN"));
    }
}
