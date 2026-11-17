package com.nikanenka.order;

/**
 * Платёж отклонён по бизнес-причине (HTTP 402).
 *
 * <p>Это НЕ ошибка инфраструктуры: ответ корректный, сервис жив, просто денег не хватает.
 * Клиент обязан уметь отличать это от {@link PaymentUnavailableException}.
 */
public class PaymentDeclinedException extends RuntimeException {

    private final String reason;

    public PaymentDeclinedException(String reason) {
        super("Payment declined: " + reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
