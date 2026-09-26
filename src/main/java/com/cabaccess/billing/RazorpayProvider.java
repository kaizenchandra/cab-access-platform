package com.cabaccess.billing;

import com.cabaccess.shared.Failure;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public class RazorpayProvider implements PaymentProvider {
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final ObjectMapper json;private final Environment env;private final String base;private final CircuitBreakerFactory<?,?> breakers;
  public RazorpayProvider(ObjectMapper json,Environment env,@Value("${cab.razorpay-base}") String base,CircuitBreakerFactory<?,?> breakers){this.json=json;this.env=env;this.base=base;this.breakers=breakers;}
  private JsonNode request(Merchant m,String method,String path,Object body){
    return breakers.create("razorpay").run(()->{
      String key=required(m.prefix()+"_KEY_ID"),secret=required(m.prefix()+"_KEY_SECRET");
      var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(8)).header("Authorization","Basic "+Base64.getEncoder().encodeToString((key+":"+secret).getBytes(StandardCharsets.UTF_8))).header("Content-Type","application/json");
      builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      try{var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()<200||response.statusCode()>=300)throw new Failure(502,"PROVIDER_HTTP_"+response.statusCode());return json.readTree(response.body());}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure(503,"PROVIDER_INTERRUPTED");}catch(java.io.IOException e){throw new Failure(503,"PROVIDER_OUTCOME_UNKNOWN");}
    });
  }
  private String required(String name){String value=env.getProperty(name);Failure.require(value!=null&&!value.isBlank(),503,"PROVIDER_CREDENTIALS_MISSING");return value;}
  public String webhookSecret(Merchant m){return required(m.prefix()+"_WEBHOOK_SECRET");}
  public ProviderOrder createOrder(Merchant m,OrderRequest r){return order(request(m,"POST","/orders",Map.of("amount",r.amount(),"currency",r.currency(),"receipt",r.id().toString(),"notes",Map.of("cab_order_id",r.id().toString()))));}
  public Optional<ProviderOrder> findOrder(Merchant m,UUID receipt){for(int skip=0;skip<1000;skip+=100){JsonNode items=request(m,"GET","/orders?count=100&skip="+skip,null).path("items");for(JsonNode x:items)if(receipt.toString().equals(x.path("receipt").asText()))return Optional.of(order(x));if(items.size()<100)return Optional.empty();}throw new Failure(409,"PROVIDER_LOOKUP_WINDOW_EXCEEDED");}
  public Payment payment(Merchant m,String id){return payment(request(m,"GET","/payments/"+identifier(id),null));}
  public List<Payment> payments(Merchant m,String order){List<Payment> result=new ArrayList<>();for(JsonNode x:request(m,"GET","/orders/"+identifier(order)+"/payments",null).path("items"))result.add(payment(x));return result;}
  public ProviderRefund refund(Merchant m,String payment,long amount,UUID receipt){return refund(request(m,"POST","/payments/"+identifier(payment)+"/refund",Map.of("amount",amount,"speed","normal","receipt",receipt.toString(),"notes",Map.of("cab_refund_id",receipt.toString()))));}
  public Optional<ProviderRefund> findRefund(Merchant m,String payment,UUID receipt){for(int skip=0;skip<1000;skip+=100){JsonNode items=request(m,"GET","/payments/"+identifier(payment)+"/refunds?count=100&skip="+skip,null).path("items");for(JsonNode x:items)if(receipt.toString().equals(x.path("receipt").asText()))return Optional.of(refund(x));if(items.size()<100)return Optional.empty();}throw new Failure(409,"PROVIDER_LOOKUP_WINDOW_EXCEEDED");}
  private String identifier(String id){Failure.require(id.matches("[A-Za-z0-9_]+"),400,"INVALID_PROVIDER_REFERENCE");return id;}
  private ProviderOrder order(JsonNode x){return new ProviderOrder(x.path("id").asText(),x.path("amount").asLong(),x.path("currency").asText());}
  private Payment payment(JsonNode x){return new Payment(x.path("id").asText(),x.path("order_id").asText(),x.path("amount").asLong(),x.path("currency").asText(),x.path("status").asText());}
  private ProviderRefund refund(JsonNode x){return new ProviderRefund(x.path("id").asText(),x.path("payment_id").asText(),x.path("amount").asLong(),x.path("status").asText(),x.path("receipt").asText());}
}
