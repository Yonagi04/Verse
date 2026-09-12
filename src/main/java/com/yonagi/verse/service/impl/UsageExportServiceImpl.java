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
            if (response.isCommitted()) return;
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
        if (Duration.between(filter.from(),filter.to()).compareTo(Duration.ofDays(properties.getRawExportMaxRangeDays()))>0)
            throw new ClientException(UsageReportingErrorCodeEnum.EXPORT_INVALID);
        long count=tokenUsageMapper.countRawExport(filter.tenantId(),filter.userId(),filter.apiKeyId(),filter.serviceId(),filter.from(),filter.to());
        if (count>properties.getExportMaxRows()) throw new ClientException(UsageReportingErrorCodeEnum.EXPORT_TOO_LARGE);
        prepare(response,"usage-raw");
        try (ExcelWriter writer=EasyExcel.write(response.getOutputStream(),UsageRawExportRow.class).autoCloseStream(false).build()) {
            WriteSheet sheet=EasyExcel.writerSheet("逐请求明细").build();
            LocalDateTime afterTime=null; Long afterId=null; long written=0;
            while (true) {
                List<UsageRawExportRow> rows=tokenUsageMapper.selectRawExportBatch(filter.tenantId(),filter.userId(),filter.apiKeyId(),
                        filter.serviceId(),filter.from(),filter.to(),afterTime,afterId,properties.getExportBatchSize());
                if (rows.isEmpty()) {
                    if (written==0) writer.write(List.of(),sheet);
                    break;
                }
                rows.forEach(this::sanitize);
                writer.write(rows,sheet); written+=rows.size();
                UsageRawExportRow last=rows.get(rows.size()-1); afterTime=last.getRequestStartedAt(); afterId=last.getId();
                if (rows.size()<properties.getExportBatchSize()) break;
                if (written>properties.getExportMaxRows()) throw new ClientException(UsageReportingErrorCodeEnum.EXPORT_TOO_LARGE);
            }
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
