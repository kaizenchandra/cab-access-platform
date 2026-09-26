package com.cabaccess.access;

import com.cabaccess.shared.Failure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Configuration
public class GateAdapters {
    @Bean
    @Profile({"local", "test"})
    GatePort simulatedGate() {
        return command -> {
        };
    }

    @Bean
    @Profile("!local & !test")
    GatePort httpGate(@Value("${cab.gate-url}") String url, @Value("${cab.gate-token}") String token, ObjectMapper json) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        return command -> {
            Failure.require(url.startsWith("https://") && !token.isBlank(), 503, "GATE_ADAPTER_UNCONFIGURED");
            try {
                var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").header("Idempotency-Key", command.id().toString()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
                var response = http.send(request, HttpResponse.BodyHandlers.discarding());
                Failure.require(response.statusCode() == 202, 503, "GATE_DISPATCH_UNKNOWN");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Failure(503, "GATE_DISPATCH_UNKNOWN");
            } catch (java.io.IOException e) {
                throw new Failure(503, "GATE_DISPATCH_UNKNOWN");
            }
        };
    }
}
