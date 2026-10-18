package com.yonagi.verse.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import java.time.*;
import java.util.List;

public final class UserUsageTestFixtures {
    public static final BudgetPeriodResolver TIME=new BudgetPeriodResolver(Clock.fixed(Instant.parse("2026-10-18T04:00:00Z"),ZoneOffset.UTC));
    public static void tables() {
        var configuration=new MybatisConfiguration();
        for (var type:List.of(UserDO.class,TokenUsageDO.class,TokenUsageCostDO.class,UserCostAlertConfigDO.class,UserCostAlertDeliveryDO.class,
                UserCostAlertEventDO.class,UserModelUsageDailyDO.class,UserModelUsageProjectionDO.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration,"personal"),type);
    }
    public static UserDO user() { var u=new UserDO(); u.setUserId(1L); u.setStatus(1); u.setDelFlag(0); u.setEmailHash("hash"); return u; }
    public static UserCostAlertConfigDO config() {
        var c=new UserCostAlertConfigDO(); c.setUserId(1L); c.setDailyEnabled(true); c.setDailyThresholdFen(new java.math.BigDecimal("1000"));
        c.setWeeklyEnabled(true); c.setWeeklyThresholdFen(new java.math.BigDecimal("1000")); c.setConfigVersion(1L); c.setChannelsJson("[\"EMAIL\"]"); return c;
    }
    public static UserCostAlertEventDO event() {
        var e=new UserCostAlertEventDO(); e.setEventId("e"); e.setUserId(1L); e.setPeriodType("DAY"); e.setPeriodStart(TIME.resolve(TIME.now()).getFirst().start());
        e.setPeriodEnd(TIME.resolve(TIME.now()).getFirst().end()); e.setChannelsJson("[\"EMAIL\"]"); e.setThresholdFen(new java.math.BigDecimal("1000")); e.setKnownCostFen(new java.math.BigDecimal("1200"));
        e.setCostComplete(false); e.setNotificationId(9L); return e;
    }
    public static UserCostAlertDeliveryDO task() {
        var d=new UserCostAlertDeliveryDO(); d.setDeliveryId(2L); d.setEventId("e"); d.setRecipientUserId(1L); d.setChannel("EMAIL"); d.setRequestId("r"); d.setContactHash("hash");
        d.setStatus("QUEUED"); d.setAttemptCount(0); d.setCreateTime(TIME.local(TIME.now())); d.setNextRetryAt(d.getCreateTime()); return d;
    }
    public static org.springframework.transaction.PlatformTransactionManager manager() {
        // 只为Service编排提供事务回调；不据此证明真实数据库事务或锁正确。
        return new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object transaction,org.springframework.transaction.TransactionDefinition definition) { }
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { }
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        };
    }
}
