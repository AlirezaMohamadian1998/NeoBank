package com.neobank.neobank.card;

import com.neobank.neobank.shared.persistence.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.YearMonth;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Inheritance(strategy = InheritanceType.JOINED)
@Table(name = "cards")
public abstract class Card extends BaseEntity {

    @Column(nullable = false, updatable = false, unique = true, length = 32)
    private String cardReference;

    @Column(nullable = false, updatable = false, length = 4)
    private String lastFourDigits;

    @Column(nullable = false)
    @Enumerated(value = EnumType.STRING)
    private CardStatus status;

    @Column(nullable = false, updatable = false, length = 7)
    @Convert(converter = YearMonthAttributeConverter.class)
    private YearMonth expirationYearMonth;

    protected Card(
            String cardReference,
            String lastFourDigits,
            YearMonth expirationYearMonth,
            YearMonth now
    ) {
        if(cardReference == null) {
            throw new IllegalArgumentException("Card reference must not be null");
        }

        if(!cardReference.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Card reference must be exactly 32 hexadecimal characters");
        }

        if(lastFourDigits == null) {
            throw new IllegalArgumentException("Last 4 digits of the card number cannot be null");
        }

        if(!lastFourDigits.matches("[0-9]{4}")) {
            throw new IllegalArgumentException("Last 4 digits must be numerical");
        }

        if(expirationYearMonth == null) {
            throw new IllegalArgumentException("Expiration cannot be null");
        }

        if(now == null) {
            throw new IllegalArgumentException("Current month cannot be null");
        }

        if(expirationYearMonth.isBefore(now)) {
            throw new IllegalArgumentException("Expiration should be at the end of this month or in future");
        }

        this.cardReference = cardReference;
        this.lastFourDigits = lastFourDigits;
        this.status = CardStatus.INACTIVE;
        this.expirationYearMonth = expirationYearMonth;
    }

    public void activateCard(YearMonth now) {
        if(isExpired(now)) {
            throw new CardExpiredException("Expired cards cannot be activated");
        }

        if(this.status != CardStatus.INACTIVE) {
            throw new IllegalStateException("Only inactive cards can be activated");
        }

        this.status = CardStatus.ACTIVE;
    }

    public void freezeCard(YearMonth now) {
        if(isExpired(now)) {
            throw new CardExpiredException("Expired cards cannot be frozen");
        }

        if(this.status != CardStatus.ACTIVE) {
            throw new IllegalStateException("Only active cards can be frozen");
        }

        this.status = CardStatus.FROZEN;
    }

    public void closeCard() {
        if(this.status == CardStatus.CLOSED) {
            throw new IllegalStateException("Card is already closed");
        }

        this.status = CardStatus.CLOSED;
    }

    public void unfreezeCard(YearMonth now) {
        if(isExpired(now)) {
            throw new CardExpiredException("Expired cards cannot be unfrozen");
        }

        if(this.status != CardStatus.FROZEN) {
            throw new IllegalStateException("Only frozen cards can be unfrozen");
        }

        this.status = CardStatus.ACTIVE;
    }

    public void blockCard(YearMonth now) {
        if(isExpired(now)) {
            throw new CardExpiredException("Expired cards cannot be blocked");
        }

        if(this.status == CardStatus.BLOCKED) {
            throw new IllegalStateException("Card is already blocked");
        }

        if(this.status == CardStatus.CLOSED) {
            throw new IllegalStateException("Closed cards cannot be blocked");
        }

        this.status = CardStatus.BLOCKED;
    }

    public boolean isExpired(YearMonth now) {
        if(now == null) {
            throw new IllegalArgumentException("Current month cannot be null");
        }

        return now.isAfter(this.expirationYearMonth);
    }
}
