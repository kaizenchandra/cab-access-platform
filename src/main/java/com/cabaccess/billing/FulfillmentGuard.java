package com.cabaccess.billing;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Hook for infrastructure preconditions; replaced with a failing stub in recovery tests. */
@Component
public class FulfillmentGuard { public void check(UUID orderId) {} }
