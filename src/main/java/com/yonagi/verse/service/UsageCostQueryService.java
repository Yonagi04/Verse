package com.yonagi.verse.service;

import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.UsageCostBreakdownRespDTO;
import com.yonagi.verse.dto.resp.UsageCostSummaryRespDTO;
import com.yonagi.verse.dto.resp.UsageCostTimeseriesRespDTO;

import java.time.OffsetDateTime;

public interface UsageCostQueryService {

    UsageCostSummaryRespDTO summary(UserContext ctx, Long tenantId, OffsetDateTime from,
                                    OffsetDateTime to, Long serviceId, Long apiKeyId);

    UsageCostTimeseriesRespDTO timeseries(UserContext ctx, Long tenantId, String granularity,
                                          OffsetDateTime from, OffsetDateTime to,
                                          Long serviceId, Long apiKeyId);

    UsageCostBreakdownRespDTO breakdown(UserContext ctx, Long tenantId, String dimension,
                                        OffsetDateTime from, OffsetDateTime to,
                                        Long serviceId, Long apiKeyId, Integer pageNum,
                                        Integer pageSize, String sortBy, String sortOrder);
}
