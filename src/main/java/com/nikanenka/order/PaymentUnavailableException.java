package com.nikanenka.order;

/**
 * Платёжный сервис недоступен: 5xx, таймаут, обрыв соединения.
 *
 * <p>В реальном коде на такое вешают retry / circuit breaker / "попробуйте позже".
 */
public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(String message) {
        super(message);
    }

    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
