package com.nikanenka.payment;

/** Платёж, который хранит payment-service. */
public record Payment(
        String paymentId,
        String orderId,
        long amountCents,
        String currency,
        String status,
        String createdAt
) {
}
