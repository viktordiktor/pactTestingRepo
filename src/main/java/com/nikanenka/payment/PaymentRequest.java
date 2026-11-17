package com.nikanenka.payment;

/** Запрос на оплату, как его присылает order-service. */
public record PaymentRequest(String orderId, long amountCents, String currency) {
}
