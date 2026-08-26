package com.neobank.neobank.fx.openexchangerates;

record OpenExchangeRatesErrorResponse(
        boolean error,
        int status,
        String message,
        String description
) {
}
