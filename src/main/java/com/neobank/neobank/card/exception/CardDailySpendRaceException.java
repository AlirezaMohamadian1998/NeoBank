package com.neobank.neobank.card.exception;

public class CardDailySpendRaceException extends RuntimeException {
    public CardDailySpendRaceException(Throwable cause) {
        super("Concurrent first purchase of the day on the same card", cause);
    }
}
