package com.yonagi.verse.service.pricing;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.mapper.LlmPricingPeakPeriodMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.LlmServicePricingMapper;
import com.yonagi.verse.dto.req.PricingConfigReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class PricingConfigurationServiceTest {

    private PricingConfigurationService service;

    @BeforeEach
    void setUp() {
        service = new PricingConfigurationService(
                mock(LlmServicePricingMapper.class),
                mock(LlmPricingPeakPeriodMapper.class),
                mock(LlmServiceMapper.class),
                mock(PricingResolver.class)
        );
    }

    @Test
    void tokenRequiredPricesMayBeZero() {
        PricingConfigReqDTO config = tokenConfig("0", null, "0");

        assertDoesNotThrow(() -> service.validate(config));
    }

    @Test
    void cacheHitPriceMayBeZeroOrAbsent() {
        assertDoesNotThrow(() -> service.validate(tokenConfig("1", "0", "1")));
        assertDoesNotThrow(() -> service.validate(tokenConfig("1", null, "1")));
    }

    @Test
    void requestPriceMayBeZero() {
        PricingConfigReqDTO config = new PricingConfigReqDTO();
        config.setEnabled(true);
        config.setBillingMode("REQUEST");
        config.setBaseRequestPriceFen(BigDecimal.ZERO);

        assertDoesNotThrow(() -> service.validate(config));
    }

    @Test
    void peakRequiredPricesMayBeZero() {
        PricingConfigReqDTO config = tokenConfig("1", null, "1");
        PricingConfigReqDTO.PeakPeriodReqDTO period = tokenPeriod("09:00", "18:00");
        period.setTokenPrices(tokenPrices("0", null, "0"));
        config.setPeakPeriods(List.of(period));

        assertDoesNotThrow(() -> service.validate(config));
    }

    @Test
    void negativePriceIsRejected() {
        PricingConfigReqDTO config = tokenConfig("-0.01", null, "1");

        assertThrows(ClientException.class, () -> service.validate(config));
    }

    @Test
    void scientificNotationCannotBypassIntegerDigitLimit() {
        PricingConfigReqDTO config = tokenConfig("1E+29", null, "1");

        assertThrows(ClientException.class, () -> service.validate(config));
    }

    @Test
    void rejectsInvalidClockTime() {
        PricingConfigReqDTO config = tokenConfig("1", null, "1");
        PricingConfigReqDTO.PeakPeriodReqDTO period = tokenPeriod("12:99", "13:00");
        config.setPeakPeriods(List.of(period));

        assertThrows(ClientException.class, () -> service.validate(config));
    }

    @Test
    void acceptsEndOfDayAsPeriodEnd() {
        PricingConfigReqDTO config = tokenConfig("1", null, "1");
        config.setPeakPeriods(List.of(tokenPeriod("23:00", "24:00")));

        assertDoesNotThrow(() -> service.validate(config));
    }

    private PricingConfigReqDTO tokenConfig(String missPrice, String hitPrice, String outputPrice) {
        PricingConfigReqDTO.TokenPricesReqDTO prices = tokenPrices(missPrice, hitPrice, outputPrice);
        PricingConfigReqDTO config = new PricingConfigReqDTO();
        config.setEnabled(true);
        config.setBillingMode("TOKEN");
        config.setBaseTokenPrices(prices);
        return config;
    }

    private PricingConfigReqDTO.PeakPeriodReqDTO tokenPeriod(String start, String end) {
        PricingConfigReqDTO.PeakPeriodReqDTO period = new PricingConfigReqDTO.PeakPeriodReqDTO();
        period.setWeekdays(List.of(1));
        period.setStartTime(start);
        period.setEndTime(end);
        period.setTokenPrices(tokenPrices("1", null, "1"));
        return period;
    }

    private PricingConfigReqDTO.TokenPricesReqDTO tokenPrices(String missPrice, String hitPrice,
                                                               String outputPrice) {
        PricingConfigReqDTO.TokenPricesReqDTO prices = new PricingConfigReqDTO.TokenPricesReqDTO();
        prices.setCacheMissInputPriceFen(new BigDecimal(missPrice));
        prices.setCacheHitInputPriceFen(hitPrice == null ? null : new BigDecimal(hitPrice));
        prices.setOutputPriceFen(new BigDecimal(outputPrice));
        return prices;
    }
}
