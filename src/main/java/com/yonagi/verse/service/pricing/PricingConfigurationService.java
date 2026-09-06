package com.yonagi.verse.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.LlmManageErrorCodeEnum;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.LlmPricingPeakPeriodDO;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.LlmServicePricingDO;
import com.yonagi.verse.dao.mapper.LlmPricingPeakPeriodMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.LlmServicePricingMapper;
import com.yonagi.verse.dto.req.PricingConfigReqDTO;
import com.yonagi.verse.dto.resp.PricingConfigRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PricingConfigurationService {

    private static final int MAX_PRICE_INTEGER_DIGITS = 18;
    private static final int MAX_PRICE_SCALE = 12;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private final LlmServicePricingMapper pricingMapper;
    private final LlmPricingPeakPeriodMapper peakMapper;
    private final LlmServiceMapper serviceMapper;
    private final PricingResolver pricingResolver;

    @Transactional(rollbackFor = Exception.class)
    public void replace(Long userId, LlmServiceDO service, PricingConfigReqDTO config) {
        if (config == null) {
            return;
        }
        if (config.getEnabled() == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_PRICING_INCOMPLETE);
        }
        // 锁随事务持有到提交，避免同一服务产生多个 effective_to=NULL 的活动版本。
        serviceMapper.lockByServiceId(service.getTenantId(), service.getServiceId());
        LocalDateTime now = LocalDateTime.now(PricingResolver.SHANGHAI);
        LlmServicePricingDO old = active(service.getTenantId(), service.getServiceId());
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            if (old != null) {
                old.setEffectiveTo(now);
                pricingMapper.updateById(old);
            }
            service.setActivePricingId(null);
            invalidateAfterCommit(service.getServiceId());
            return;
        }
        validate(config);
        if (old != null) {
            old.setEffectiveTo(now);
            pricingMapper.updateById(old);
        }
        LlmServicePricingDO created = new LlmServicePricingDO();
        created.setPricingId(SnowflakeIdUtil.nextId());
        created.setTenantId(service.getTenantId());
        created.setServiceId(service.getServiceId());
        created.setBillingMode(config.getBillingMode().toUpperCase(Locale.ROOT));
        created.setCurrency("CNY");
        created.setEffectiveFrom(now);
        created.setCreatedBy(userId);
        if (BillingMode.TOKEN.name().equals(created.getBillingMode())) {
            created.setBaseCacheMissInputPriceFen(config.getBaseTokenPrices().getCacheMissInputPriceFen());
            created.setBaseCacheHitInputPriceFen(config.getBaseTokenPrices().getCacheHitInputPriceFen());
            created.setBaseOutputPriceFen(config.getBaseTokenPrices().getOutputPriceFen());
        } else {
            created.setBaseRequestPriceFen(config.getBaseRequestPriceFen());
        }
        pricingMapper.insert(created);
        for (PricingConfigReqDTO.PeakPeriodReqDTO rule : safeRules(config)) {
            LlmPricingPeakPeriodDO period = new LlmPricingPeakPeriodDO();
            period.setPeriodId(SnowflakeIdUtil.nextId());
            period.setPricingId(created.getPricingId());
            period.setWeekdayMask(toMask(rule.getWeekdays()));
            period.setStartMinute(toMinute(rule.getStartTime()));
            period.setEndMinute(toMinute(rule.getEndTime()));
            if (BillingMode.TOKEN.name().equals(created.getBillingMode())) {
                period.setPeakCacheMissInputPriceFen(rule.getTokenPrices().getCacheMissInputPriceFen());
                period.setPeakCacheHitInputPriceFen(rule.getTokenPrices().getCacheHitInputPriceFen());
                period.setPeakOutputPriceFen(rule.getTokenPrices().getOutputPriceFen());
            } else {
                period.setPeakRequestPriceFen(rule.getRequestPriceFen());
            }
            peakMapper.insert(period);
        }
        service.setActivePricingId(created.getPricingId());
        invalidateAfterCommit(service.getServiceId());
    }

    public PricingConfigRespDTO current(Long serviceId) {
        LlmServicePricingDO pricing = pricingMapper.selectOne(Wrappers.lambdaQuery(LlmServicePricingDO.class)
                .eq(LlmServicePricingDO::getServiceId, serviceId).isNull(LlmServicePricingDO::getEffectiveTo)
                .orderByDesc(LlmServicePricingDO::getEffectiveFrom).last("LIMIT 1"));
        PricingConfigRespDTO result = new PricingConfigRespDTO();
        if (pricing == null) {
            result.setEnabled(false);
            return result;
        }
        result.setEnabled(true);
        result.setPricingId(pricing.getPricingId());
        result.setBillingMode(pricing.getBillingMode());
        result.setCurrency(pricing.getCurrency());
        if (BillingMode.TOKEN.name().equals(pricing.getBillingMode())) {
            PricingConfigRespDTO.TokenPrices prices = new PricingConfigRespDTO.TokenPrices();
            prices.setCacheMissInputPriceFen(pricing.getBaseCacheMissInputPriceFen());
            prices.setCacheHitInputPriceFen(pricing.getBaseCacheHitInputPriceFen());
            prices.setOutputPriceFen(pricing.getBaseOutputPriceFen());
            result.setBaseTokenPrices(prices);
        } else {
            result.setBaseRequestPriceFen(pricing.getBaseRequestPriceFen());
        }
        result.setPeakPeriods(peakMapper.selectList(Wrappers.lambdaQuery(LlmPricingPeakPeriodDO.class)
                        .eq(LlmPricingPeakPeriodDO::getPricingId, pricing.getPricingId()))
                .stream()
                .map(period -> toResponse(period, pricing))
                .toList());
        return result;
    }

    private PricingConfigRespDTO.PeakPeriod toResponse(LlmPricingPeakPeriodDO p, LlmServicePricingDO pricing) {
        PricingConfigRespDTO.PeakPeriod value = new PricingConfigRespDTO.PeakPeriod();
        value.setPeriodId(p.getPeriodId());
        value.setWeekdays(fromMask(p.getWeekdayMask()));
        value.setStartTime(toTime(p.getStartMinute()));
        value.setEndTime(toTime(p.getEndMinute()));
        if (BillingMode.TOKEN.name().equals(pricing.getBillingMode())) {
            PricingConfigRespDTO.TokenPrices prices = new PricingConfigRespDTO.TokenPrices();
            prices.setCacheMissInputPriceFen(p.getPeakCacheMissInputPriceFen());
            prices.setCacheHitInputPriceFen(p.getPeakCacheHitInputPriceFen());
            prices.setOutputPriceFen(p.getPeakOutputPriceFen());
            value.setTokenPrices(prices);
        } else {
            value.setRequestPriceFen(p.getPeakRequestPriceFen());
        }
        return value;
    }

    public void validate(PricingConfigReqDTO config) {
        if (!Boolean.TRUE.equals(config.getEnabled()) || config.getBillingMode() == null) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_PRICING_INCOMPLETE);
        }
        BillingMode mode;
        try {
            mode = BillingMode.valueOf(config.getBillingMode().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_PRICING_INCOMPLETE);
        }
        if (mode == BillingMode.TOKEN) {
            if (config.getBaseTokenPrices() == null || config.getBaseRequestPriceFen() != null
                    || config.getBaseTokenPrices().getCacheMissInputPriceFen() == null
                    || config.getBaseTokenPrices().getOutputPriceFen() == null) {
                throw new ClientException(LlmManageErrorCodeEnum.LLM_PRICING_INCOMPLETE);
            }
            prices(config.getBaseTokenPrices().getCacheMissInputPriceFen(),
                    config.getBaseTokenPrices().getCacheHitInputPriceFen(),
                    config.getBaseTokenPrices().getOutputPriceFen());
        } else {
            if (config.getBaseTokenPrices() != null || config.getBaseRequestPriceFen() == null) {
                throw new ClientException(LlmManageErrorCodeEnum.LLM_PRICING_INCOMPLETE);
            }
            price(config.getBaseRequestPriceFen());
        }
        validateRules(mode, safeRules(config));
    }

    private void validateRules(BillingMode mode, List<PricingConfigReqDTO.PeakPeriodReqDTO> rules) {
        List<int[]>[] perDay = new List[7];
        for (int i = 0; i < 7; i++) perDay[i] = new ArrayList<>();
        for (PricingConfigReqDTO.PeakPeriodReqDTO rule : rules) {
            int start = toMinute(rule.getStartTime()), end = toMinute(rule.getEndTime());
            if (start >= end || rule.getWeekdays() == null || rule.getWeekdays().isEmpty()) {
                throw new ClientException(LlmManageErrorCodeEnum.LLM_PEAK_PERIOD_INVALID);
            }
            if (mode == BillingMode.TOKEN) {
                if (rule.getTokenPrices() == null || rule.getRequestPriceFen() != null
                        || rule.getTokenPrices().getCacheMissInputPriceFen() == null
                        || rule.getTokenPrices().getOutputPriceFen() == null) {
                    throw new ClientException(LlmManageErrorCodeEnum.LLM_PRICING_INCOMPLETE);
                }
                prices(rule.getTokenPrices().getCacheMissInputPriceFen(),
                        rule.getTokenPrices().getCacheHitInputPriceFen(),
                        rule.getTokenPrices().getOutputPriceFen());
            } else {
                if (rule.getTokenPrices() != null || rule.getRequestPriceFen() == null) {
                    throw new ClientException(LlmManageErrorCodeEnum.LLM_PRICING_INCOMPLETE);
                }
                price(rule.getRequestPriceFen());
            }
            for (Integer day : rule.getWeekdays()) {
                if (day == null || day < 1 || day > 7) {
                    throw new ClientException(LlmManageErrorCodeEnum.LLM_PEAK_PERIOD_INVALID);
                }
                perDay[day - 1].add(new int[]{start, end});
            }
        }
        for (List<int[]> values : perDay) {
            values.sort(Comparator.comparingInt(v -> v[0]));
            for (int i = 1; i < values.size(); i++) {
                if (values.get(i)[0] < values.get(i - 1)[1]) {
                    throw new ClientException(LlmManageErrorCodeEnum.LLM_PEAK_PERIOD_INVALID);
                }
            }
        }
    }

    private void prices(BigDecimal miss, BigDecimal hit, BigDecimal output) {
        price(miss);
        if (hit != null) {
            // 缓存命中允许免费；未配置时由解析器回退到未命中单价。
            price(hit);
        }
        price(output);
    }

    private void price(BigDecimal value) {
        // 显式零价格代表免费计费，与未启用计费（无价格版本）语义不同。
        if (value == null || value.signum() < 0) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_PRICE_INVALID);
        }
        BigDecimal normalized = value.stripTrailingZeros();
        int integerDigits = Math.max(normalized.precision() - normalized.scale(), 0);
        if (normalized.scale() > MAX_PRICE_SCALE || integerDigits > MAX_PRICE_INTEGER_DIGITS) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_PRICE_INVALID);
        }
    }

    private void invalidateAfterCommit(Long serviceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            pricingResolver.invalidateCurrent(serviceId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pricingResolver.invalidateCurrent(serviceId);
            }
        });
    }

    private LlmServicePricingDO active(Long tenantId, Long serviceId) {
        return pricingMapper.selectOne(Wrappers.lambdaQuery(LlmServicePricingDO.class)
                .eq(LlmServicePricingDO::getTenantId, tenantId)
                .eq(LlmServicePricingDO::getServiceId, serviceId)
                .isNull(LlmServicePricingDO::getEffectiveTo)
                .orderByDesc(LlmServicePricingDO::getEffectiveFrom)
                .last("LIMIT 1"));
    }

    private List<PricingConfigReqDTO.PeakPeriodReqDTO> safeRules(PricingConfigReqDTO config) {
        return config.getPeakPeriods() == null ? List.of() : config.getPeakPeriods();
    }

    private int toMask(List<Integer> days) {
        int result = 0;
        for (Integer day : days) {
            result |= 1 << (day - 1);
        }
        return result;
    }

    private List<Integer> fromMask(int mask) {
        List<Integer> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            if ((mask & (1 << i)) != 0) {
                days.add(i + 1);
            }
        }
        return days;
    }

    private int toMinute(String value) {
        if ("24:00".equals(value)) {
            return 24 * 60;
        }
        try {
            if (value == null || value.length() != 5) {
                throw new DateTimeParseException("时间格式非法", String.valueOf(value), 0);
            }
            LocalTime time = LocalTime.parse(value, TIME_FORMATTER);
            return time.getHour() * 60 + time.getMinute();
        } catch (DateTimeParseException ex) {
            throw new ClientException(LlmManageErrorCodeEnum.LLM_PEAK_PERIOD_INVALID);
        }
    }

    private String toTime(int minute) {
        return String.format("%02d:%02d", minute / 60, minute % 60);
    }
}
