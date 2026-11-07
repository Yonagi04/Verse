package com.yonagi.verse.service.impl;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.mapper.TokenUsageMapper;
import com.yonagi.verse.dao.projection.UsageRawExportRow;
import com.yonagi.verse.dto.export.UsageBreakdownExportRow;
import com.yonagi.verse.dto.export.UsageTimeseriesExportRow;
import com.yonagi.verse.dto.resp.UsageBreakdownRespDTO;
import com.yonagi.verse.dto.resp.UsageReportRespDTO;
import com.yonagi.verse.service.UsageExportService;
import com.yonagi.verse.service.UsageReportService;
import com.yonagi.verse.service.reporting.UsageReportFilter;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Semaphore;

/** 有界、同步的用量 Excel 导出实现。 */
@Slf4j
@Service
public class UsageExportServiceImpl implements UsageExportService {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final UsageReportService reportService;
    private final TokenUsageMapper tokenUsageMapper;
    private final UsageReportingProperties properties;
    private final MeterRegistry meterRegistry;
    private final Semaphore permits;

    public UsageExportServiceImpl(UsageReportService reportService, TokenUsageMapper tokenUsageMapper,
                                  UsageReportingProperties properties, MeterRegistry meterRegistry) {
        this.reportService = reportService;
        this.tokenUsageMapper = tokenUsageMapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.permits = new Semaphore(Math.max(1, properties.getExportMaxConcurrency()), true);
    }

    @Override
    public void export(HttpServletResponse response, UserContext context, Long tenantId, UsageExportType type,
                       UsageGranularity granularity, LocalDateTime from, LocalDateTime to, Long userId,
                       Long apiKeyId, Long serviceId, UsageBreakdownDimension dimension,
                       UsageBreakdownOrder orderBy, int limit) {
        long started = System.nanoTime();
        if (!permits.tryAcquire()) {
            record(type,"busy",started);
            throw new ClientException(UsageReportingErrorCodeEnum.EXPORT_BUSY);
        }
        try {
            UsageReportFilter filter = reportService.resolveFilter(context,tenantId,granularity,from,to,userId,apiKeyId,serviceId);
            switch (type) {
                case TIMESERIES -> writeTimeseries(response,context,filter);
                case BREAKDOWN -> writeBreakdown(response,context,filter,dimension,orderBy,limit);
                case RAW -> writeRaw(response,filter);
            }
            log.info("usage_export_completed tenantId={} userId={} type={} elapsedMs={}",tenantId,context.getUserId(),type,
                    Duration.ofNanos(System.nanoTime()-started).toMillis());
            record(type,"success",started);
        } catch (ClientException e) {
            record(type,"rejected",started);
            throw e;
        } catch (Exception e) {
            log.error("usage_export_failed tenantId={} userId={} type={}",tenantId,context.getUserId(),type,e);
            record(type,"failed",started);
            if (response.isCommitted()) {
                return;
            }
            throw new ServerException("生成用量报表失败",e,UsageReportingErrorCodeEnum.EXPORT_FAILED);
        } finally {
            permits.release();
        }
    }

    private void record(UsageExportType type,String outcome,long started) {
        meterRegistry.counter("verse.usage.export.total","type",type.name().toLowerCase(),"outcome",outcome).increment();
        Timer.builder("verse.usage.export.duration").tag("type",type.name().toLowerCase()).tag("outcome",outcome)
                .register(meterRegistry).record(Duration.ofNanos(System.nanoTime()-started));
    }

    private void writeTimeseries(HttpServletResponse response, UserContext context, UsageReportFilter filter) throws IOException {
        UsageReportRespDTO result=reportService.query(context,filter.tenantId(),filter.granularity(),filter.from(),filter.to(),
                filter.userId(),filter.apiKeyId(),filter.serviceId());
        List<UsageTimeseriesExportRow> rows=result.getPoints().stream().map(point->{
            UsageTimeseriesExportRow row=new UsageTimeseriesExportRow(); row.setBucket(point.getBucket());
            row.setInputTokens(point.getInputTokens()); row.setOutputTokens(point.getOutputTokens()); row.setTotalTokens(point.getTotalTokens());
            row.setRequestCount(point.getRequestCount()); row.setEstimatedCostFen(point.getEstimatedCostFen());
            row.setExactUsageCount(point.getExactUsageCount()); row.setEstimatedUsageCount(point.getEstimatedUsageCount());
            row.setUnknownUsageCount(point.getUnknownUsageCount()); return row;
        }).toList();
        prepare(response,"usage-timeseries");
        EasyExcel.write(response.getOutputStream(),UsageTimeseriesExportRow.class).autoCloseStream(false).sheet("时间序列").doWrite(rows);
    }

    private void writeBreakdown(HttpServletResponse response, UserContext context, UsageReportFilter filter,
                                UsageBreakdownDimension dimension, UsageBreakdownOrder orderBy, int limit) throws IOException {
        UsageBreakdownRespDTO result=reportService.breakdown(context,filter.tenantId(),filter.granularity(),filter.from(),filter.to(),
                filter.userId(),filter.apiKeyId(),filter.serviceId(),dimension,orderBy,limit);
        List<UsageBreakdownExportRow> rows=result.getItems().stream().map(item->{
            UsageBreakdownExportRow row=new UsageBreakdownExportRow(); row.setId(safe(item.getId())); row.setLabel(safe(item.getLabel())); row.setRatio(item.getRatio());
            row.setInputTokens(item.getMetrics().getInputTokens()); row.setOutputTokens(item.getMetrics().getOutputTokens());
            row.setTotalTokens(item.getMetrics().getTotalTokens()); row.setRequestCount(item.getMetrics().getRequestCount());
            row.setEstimatedCostFen(item.getMetrics().getEstimatedCostFen()); row.setUncalculableCount(item.getMetrics().getUncalculableCount());
            row.setUnpricedCount(item.getMetrics().getUnpricedCount()); return row;
        }).toList();
        prepare(response,"usage-breakdown");
        EasyExcel.write(response.getOutputStream(),UsageBreakdownExportRow.class).autoCloseStream(false).sheet("维度排行").doWrite(rows);
    }

    private void writeRaw(HttpServletResponse response, UsageReportFilter filter) throws IOException {
        Duration requestedRange = Duration.between(filter.from(), filter.to());
        if (requestedRange.compareTo(Duration.ofDays(properties.getRawExportMaxRangeDays())) > 0) {
            throw new ClientException(UsageReportingErrorCodeEnum.EXPORT_INVALID);
        }
        int maxRows = properties.getExportMaxRows();
        long count = tokenUsageMapper.countRawExport(filter.tenantId(), filter.userId(), filter.apiKeyId(),
                filter.serviceId(), filter.from(), filter.to());
        if (count > maxRows) {
            throw new ClientException(UsageReportingErrorCodeEnum.EXPORT_TOO_LARGE);
        }

        ExcelWriter writer = EasyExcel.write(response.getOutputStream(), UsageRawExportRow.class)
                .autoCloseStream(false).writeExcelOnException(false).build();
        // 正常 finish 才会发送文件。失败时清理未完成的工作簿，响应仍可返回业务错误。
        try (Closeable discardWorkbook = () -> writer.writeContext().finish(true)) {
            writeRawRows(writer, filter, maxRows);
            prepare(response, "usage-raw");
            writer.finish();
        }
    }

    private void writeRawRows(ExcelWriter writer, UsageReportFilter filter, int maxRows) {
        WriteSheet sheet = EasyExcel.writerSheet("逐请求明细").build();
        int batchSize = properties.getExportBatchSize();
        int remainingRows = maxRows;
        LocalDateTime afterTime = null;
        Long afterId = null;
        while (true) {
            // 计数后可能有新用量入库，多读一行可区分恰好到上限与超限。
            int limit = (int) Math.min(batchSize, remainingRows + 1L);
            List<UsageRawExportRow> rows = tokenUsageMapper.selectRawExportBatch(filter.tenantId(), filter.userId(),
                    filter.apiKeyId(), filter.serviceId(), filter.from(), filter.to(), afterTime, afterId, limit);
            if (rows.size() > remainingRows) {
                throw new ClientException(UsageReportingErrorCodeEnum.EXPORT_TOO_LARGE);
            }
            if (rows.isEmpty()) {
                if (remainingRows == maxRows) {
                    writer.write(List.of(), sheet);
                }
                return;
            }
            rows.forEach(this::sanitize);
            writer.write(rows, sheet);
            if (rows.size() < limit) {
                return;
            }
            remainingRows -= rows.size();
            UsageRawExportRow last = rows.getLast();
            afterTime = last.getRequestStartedAt();
            afterId = last.getId();
        }
    }

    private void prepare(HttpServletResponse response,String prefix) {
        String filename=prefix+'-'+LocalDateTime.now().format(FILE_TIME)+".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(filename,StandardCharsets.UTF_8).build().toString());
        response.setHeader("X-Content-Type-Options","nosniff");
    }

    /** 防止来自数据库的文本被 Excel 当作公式执行。 */
    private String safe(String value) {
        if (value==null) return null;
        String stripped=value.stripLeading();
        return !stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0 ? '\''+value : value;
    }

    private void sanitize(UsageRawExportRow row) {
        row.setRequestId(safe(row.getRequestId())); row.setMemberLabel(safe(row.getMemberLabel()));
        row.setApiKeyLabel(safe(row.getApiKeyLabel())); row.setServiceLabel(safe(row.getServiceLabel()));
        row.setModel(safe(row.getModel())); row.setStatus(safe(row.getStatus())); row.setUsageSource(safe(row.getUsageSource()));
        row.setCostStatus(safe(row.getCostStatus())); row.setCurrency(safe(row.getCurrency()));
    }
}
