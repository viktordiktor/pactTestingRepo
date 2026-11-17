package com.nikanenka.order;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Результат успешной авторизации платежа.
 *
 * @param paymentId   идентификатор платежа в payment-service
 * @param status      статус (AUTHORIZED)
 * @param amountCents сумма
 * @param currency    валюта
 * @param createdAt   время создания в ISO-8601
 */
// Tolerant reader: провайдер может добавить новое поле, и клиент не должен падать.
// Ровно та же идея лежит в основе контрактов: контракт фиксирует только то, что реально нужно consumer-у.
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentResult(
        String paymentId,
        String status,
        long amountCents,
        String currency,
        String createdAt
) {
}
