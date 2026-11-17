package com.nikanenka.order;

/**
 * Заказ, который мы хотим оплатить.
 *
 * <p>Сумма хранится в минорных единицах (центах/копейках) как целое число.
 * Это не случайность: во-первых, деньги во float - это боль, во-вторых,
 * целые числа гораздо проще описывать в контракте, чем BigDecimal.
 *
 * @param orderId     идентификатор заказа
 * @param amountCents сумма в центах
 * @param currency    трёхбуквенный код валюты (EUR/USD/GBP)
 */
public record Order(String orderId, long amountCents, String currency) {
}
