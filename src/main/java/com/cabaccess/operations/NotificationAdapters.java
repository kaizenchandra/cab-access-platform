package com.cabaccess.operations;

import com.cabaccess.shared.Failure;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class NotificationAdapters {
  @Bean @Profile({"local","test"}) NotificationPort simulatedNotification(){return message->"sim_delivery_"+message.id();}
  @Bean @Profile("!local & !test") NotificationPort httpNotification(@Value("${cab.notification-url}") String url,@Value("${cab.notification-token}") String token,ObjectMapper json){var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();return message->{Failure.require(url.startsWith("https://")&&!token.isBlank(),503,"NOTIFICATION_ADAPTER_UNCONFIGURED");try{var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key",message.id().toString()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message))).build();var response=http.send(request,HttpResponse.BodyHandlers.ofString());Failure.require(response.statusCode()==200,503,"NOTIFICATION_DELIVERY_UNCONFIRMED");String receipt=json.readTree(response.body()).path("deliveryReference").asText();Failure.require(!receipt.isBlank(),503,"NOTIFICATION_DELIVERY_UNCONFIRMED");return receipt;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure(503,"NOTIFICATION_INTERRUPTED");}catch(java.io.IOException e){throw new Failure(503,"NOTIFICATION_UNAVAILABLE");}};}
}
