package com.cabaccess;

import com.cabaccess.billing.PaymentProvider;
import com.cabaccess.billing.RazorpayProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RazorpayContractTest {
    WireMockServer server;
    RazorpayProvider provider;
    PaymentProvider.Merchant merchant;

    @BeforeEach
    void setup() {
        server = new WireMockServer(0);
        server.start();
        CircuitBreakerFactory factory = mock(CircuitBreakerFactory.class);
        CircuitBreaker pass = new CircuitBreaker() {
            public <T> T run(java.util.function.Supplier<T> action, java.util.function.Function<Throwable, T> fallback) {
                try {
                    return action.get();
                } catch (Throwable e) {
                    return fallback.apply(e);
                }
            }
        };
        when(factory.create(anyString())).thenReturn(pass);
        provider = new RazorpayProvider(new ObjectMapper(), new MockEnvironment().withProperty("TEST_KEY_ID", "rzp_test_synthetic").withProperty("TEST_KEY_SECRET", "synthetic-secret").withProperty("TEST_WEBHOOK_SECRET", "synthetic-webhook"), server.baseUrl(), factory);
        merchant = new PaymentProvider.Merchant(UUID.randomUUID(), UUID.randomUUID(), "razorpay", "TEST");
    }

    @AfterEach
    void close() {
        server.stop();
    }

    @Test
    void createOrderUsesExactMinorUnitsAndReceipt() {
        UUID receipt = UUID.randomUUID();
        server.stubFor(post(urlEqualTo("/orders")).withBasicAuth("rzp_test_synthetic", "synthetic-secret").withRequestBody(matchingJsonPath("$.amount", equalTo("123400"))).withRequestBody(matchingJsonPath("$.receipt", equalTo(receipt.toString()))).willReturn(okJson("{\"id\":\"order_test\",\"amount\":123400,\"currency\":\"INR\"}")));
        var o = provider.createOrder(merchant, new PaymentProvider.OrderRequest(receipt, 123400, "INR"));
        assertEquals("order_test", o.id());
        assertEquals(123400, o.amount());
        server.verify(1, postRequestedFor(urlEqualTo("/orders")));
    }

    @Test
    void fetchCapturedPaymentAndRefundReceiptContract() {
        UUID receipt = UUID.randomUUID();
        server.stubFor(get(urlEqualTo("/payments/pay_test")).willReturn(okJson("{\"id\":\"pay_test\",\"order_id\":\"order_test\",\"amount\":123400,\"currency\":\"INR\",\"status\":\"captured\"}")));
        assertEquals("captured", provider.payment(merchant, "pay_test").status());
        String refund = "{\"id\":\"rfnd_test\",\"payment_id\":\"pay_test\",\"amount\":100,\"status\":\"processed\",\"receipt\":\"" + receipt + "\"}";
        server.stubFor(post(urlEqualTo("/payments/pay_test/refund")).withRequestBody(matchingJsonPath("$.amount", equalTo("100"))).withRequestBody(matchingJsonPath("$.receipt", equalTo(receipt.toString()))).willReturn(okJson(refund)));
        assertEquals("processed", provider.refund(merchant, "pay_test", 100, receipt).status());
        server.stubFor(get(urlEqualTo("/payments/pay_test/refunds?count=100&skip=0")).willReturn(okJson("{\"items\":[" + refund + "]}")));
        assertEquals("rfnd_test", provider.findRefund(merchant, "pay_test", receipt).orElseThrow().id());
    }

    @Test
    void failedExternalWriteIsNotAutomaticallyRetried() {
        server.stubFor(post(urlEqualTo("/orders")).willReturn(serverError()));
        assertThrows(RuntimeException.class, () -> provider.createOrder(merchant, new PaymentProvider.OrderRequest(UUID.randomUUID(), 100, "INR")));
        server.verify(1, postRequestedFor(urlEqualTo("/orders")));
    }
}
