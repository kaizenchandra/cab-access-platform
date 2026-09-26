package com.cabaccess.billing;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Hook for infrastructure preconditions; replaced with a failing stub in recovery tests.
 */
@Component
public class FulfillmentGuard {
    public void check(UUID orderId) {
    }
}
