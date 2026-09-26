package com.cabaccess.billing;

import java.util.*;

public interface PaymentProvider {
  record Merchant(UUID id,UUID tenant,String provider,String prefix){}
  record OrderRequest(UUID id,long amount,String currency){}
  record ProviderOrder(String id,long amount,String currency){}
  record Payment(String id,String orderId,long amount,String currency,String status){}
  record ProviderRefund(String id,String paymentId,long amount,String status,String receipt){}
  ProviderOrder createOrder(Merchant merchant,OrderRequest request);
  Optional<ProviderOrder> findOrder(Merchant merchant,UUID receipt);
  Payment payment(Merchant merchant,String paymentId);
  List<Payment> payments(Merchant merchant,String orderId);
  ProviderRefund refund(Merchant merchant,String paymentId,long amount,UUID receipt);
  Optional<ProviderRefund> findRefund(Merchant merchant,String paymentId,UUID receipt);
  String webhookSecret(Merchant merchant);
}
