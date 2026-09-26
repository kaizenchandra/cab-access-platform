package com.cabaccess.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class RuntimeSafety {
    public RuntimeSafety(Environment env, @Value("${cab.local-delivery}") boolean local) {
        boolean development = env.matchesProfiles("local", "test");
        Failure.require(development || !local, 500, "DEVELOPMENT_DELIVERY_FORBIDDEN");
    }
}
