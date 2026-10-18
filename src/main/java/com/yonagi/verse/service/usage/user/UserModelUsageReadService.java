package com.yonagi.verse.service.usage.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.dao.entity.UserModelUsageDailyDO;
import com.yonagi.verse.dao.mapper.UserModelUsageDailyMapper;
import com.yonagi.verse.dao.mapper.UserModelUsageProjectionMapper;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 按本人实时读取有界日汇总，不缓存；只判断已入库调用的处理进度。 */
@Service @RequiredArgsConstructor
public class UserModelUsageReadService {
    private final UserModelUsageDailyMapper daily;
    private final UserModelUsageProjectionMapper work;
    private final UserModelUsageAccessPolicy access;
    private final BudgetPeriodResolver time;

    public Snapshot snapshot(Long userId,LocalDate from,LocalDate to) {
        var rows=daily.selectList(Wrappers.lambdaQuery(UserModelUsageDailyDO.class)
                .eq(UserModelUsageDailyDO::getUserId,userId)
                .ge(UserModelUsageDailyDO::getUsageDate,from).le(UserModelUsageDailyDO::getUsageDate,to));
        Map<LocalDate,DailyUsage> days=new HashMap<>();
        LocalDateTime updatedAt=null;
        for (var row:rows) {
            days.put(row.getUsageDate(),new DailyUsage(row.getSuccessCount(),row.getKnownCostFen(),row.getUnknownCostCount()));
            if (row.getUpdatedAt()!=null && (updatedAt==null || row.getUpdatedAt().isAfter(updatedAt))) updatedAt=row.getUpdatedAt();
        }
        var incomplete=work.incompleteDates(userId,from.atStartOfDay(),to.plusDays(1).atStartOfDay());
        boolean conflict=work.hasConflict(userId), undated=work.hasUndated(userId);
        var oldest=work.oldest(userId);
        long lag=oldest==null ? 0L : Math.max(0,Duration.between(oldest,time.local(time.now())).getSeconds());
        return new Snapshot(Map.copyOf(days),access.collecting() ? "READY" : "UNAVAILABLE",updatedAt,lag,
                conflict,undated,Set.copyOf(incomplete));
    }

    /** 调用者持有用户锁；费用只比较已知累计，未知费用不按零费用宣称完整。 */
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public Optional<PeriodCost> periodCost(Long userId,LocalDate from,LocalDate toExclusive) {
        if (!access.collecting()) return Optional.empty();
        var snapshot=snapshot(userId,from,toExclusive.minusDays(1));
        if (snapshot.conflict() || !"READY".equals(snapshot.availability())) return Optional.empty();
        BigDecimal known=snapshot.days().values().stream().map(DailyUsage::knownCostFen).reduce(BigDecimal.ZERO,BigDecimal::add);
        boolean complete=snapshot.days().values().stream().allMatch(row -> row.unknownCostCount()==0);
        for (var date=from;!date.isAfter(time.local(time.now()).toLocalDate()) && date.isBefore(toExclusive);date=date.plusDays(1))
            complete &= snapshot.complete(date);
        return Optional.of(new PeriodCost(known,complete));
    }

    public record Snapshot(
            /** 已入库并完成处理的逐日汇总。 */ Map<LocalDate,DailyUsage> days,
            /** 当前统计处理是否可用。 */ String availability,
            /** 当前范围最近汇总时间。 */ LocalDateTime updatedAt,
            /** 最老未完成工作项的年龄秒。 */ Long lagSeconds,
            /** 是否存在同一逻辑请求的矛盾事实。 */ boolean conflict,
            /** 是否存在无法归属日期的调用。 */ boolean undated,
            /** 已入库但尚未处理完成的日期，包含缺少工作项的调用。 */ Set<LocalDate> incomplete) {
        public boolean invalid() { return conflict || undated; }
        /** 不认证过去是否漏记，只判断已入库调用是否处理完成；费用可计算性另行判断。 */
        public boolean complete(LocalDate date) {
            return "READY".equals(availability) && !invalid() && !incomplete.contains(date);
        }
    }

    public record DailyUsage(
            /** 当日成功逻辑调用数。 */ long successCount,
            /** 当日已知预估费用，单位分。 */ BigDecimal knownCostFen,
            /** 当日费用未知的逻辑调用数。 */ long unknownCostCount) {}

    public record PeriodCost(
            /** 周期内已知费用下界，保持原始精度。 */ BigDecimal knownCostFen,
            /** 已入库调用均已处理且费用可确定。 */ boolean complete) {}
}
