package com.yonagi.verse.dao.mapper;

import com.yonagi.verse.support.MySqlTestDatabase;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.yonagi.verse.dto.req.NotificationListReqDTO;
import com.yonagi.verse.dto.resp.NotificationListRespDTO;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NotificationRecipientMapperIntegrationTest {

    private MySqlTestDatabase database;
    private JdbcTemplate jdbcTemplate;
    private SqlSession session;
    private NotificationRecipientMapper mapper;
    private final Instant now = Instant.parse("2026-10-04T04:00:00Z");
    private final long startTime = now.minus(90, ChronoUnit.DAYS).toEpochMilli();

    @BeforeEach
    void setUp() throws Exception {
        database = MySqlTestDatabase.create();
        jdbcTemplate = new JdbcTemplate(database);
        jdbcTemplate.execute("CREATE TABLE t_notification (notification_id BIGINT PRIMARY KEY, title VARCHAR(100), " +
                "content VARCHAR(100), type VARCHAR(32), severity VARCHAR(32))");
        jdbcTemplate.execute("CREATE TABLE t_notification_recipient (id BIGINT PRIMARY KEY, notification_id BIGINT, " +
                "user_id BIGINT, is_read INT, create_time DATETIME(3))");

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(database);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(NotificationRecipientMapper.class);
        factoryBean.setConfiguration(configuration);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        factoryBean.setPlugins(interceptor);
        session = factoryBean.getObject().openSession(true);
        mapper = session.getMapper(NotificationRecipientMapper.class);
    }

    @AfterEach
    void tearDown() {
        if (session != null) session.close();
        if (database != null) database.close();
    }

    @Test
    void everyFilterCombinationCountsAndPaginatesTheSameUserRecords() {
        long id = 1;
        for (String type : List.of("SYSTEM", "ANNOUNCEMENT")) {
            for (String severity : List.of("INFO", "WARNING", "CRITICAL")) {
                for (int isRead : List.of(0, 1)) {
                    insert(id++, 10L, type, severity, isRead, now);
                }
            }
        }
        // 其他用户和九十天之前的通知不应混入记录或总数。
        insert(id++, 20L, "SYSTEM", "INFO", 0, now);
        insert(id, 10L, "SYSTEM", "INFO", 0, now.minus(91, ChronoUnit.DAYS));

        for (String type : new String[]{null, "SYSTEM", "ANNOUNCEMENT"}) {
            for (String severity : new String[]{null, "INFO", "WARNING", "CRITICAL"}) {
                for (Integer isRead : new Integer[]{null, 0, 1}) {
                    NotificationListReqDTO query = new NotificationListReqDTO();
                    query.setType(type);
                    query.setSeverity(severity);
                    query.setIsRead(isRead);
                    int expectedTotal = (type == null ? 2 : 1) * (severity == null ? 3 : 1) * (isRead == null ? 2 : 1);
                    List<Long> actualIds = new ArrayList<>();
                    for (int pageNum = 1; pageNum <= (expectedTotal + 1) / 2; pageNum++) {
                        Page<NotificationListRespDTO.NotificationInfo> page = mapper.selectPageByUserIdAndStartTime(
                                new Page<>(pageNum, 2), 10L, startTime, query);
                        assertEquals(expectedTotal, page.getTotal());
                        assertEquals(Math.min(2, expectedTotal - (pageNum - 1) * 2), page.getRecords().size());
                        for (NotificationListRespDTO.NotificationInfo record : page.getRecords()) {
                            if (type != null) assertEquals(type, record.getType());
                            if (severity != null) assertEquals(severity, record.getSeverity());
                            if (isRead != null) assertEquals(isRead == 1, record.getIsRead());
                            actualIds.add(record.getNotificationId());
                        }
                    }
                    assertEquals(expectedTotal, actualIds.stream().distinct().count());
                    for (int i = 1; i < actualIds.size(); i++) {
                        assertFalse(actualIds.get(i) >= actualIds.get(i - 1));
                    }
                }
            }
        }
    }

    @Test
    void filterWithNoMatchesReturnsZeroTotalAndEmptyPage() {
        insert(1L, 10L, "SYSTEM", "INFO", 0, now);
        NotificationListReqDTO query = new NotificationListReqDTO();
        query.setIsRead(1);
        Page<NotificationListRespDTO.NotificationInfo> page = mapper.selectPageByUserIdAndStartTime(
                new Page<>(1, 10), 10L, startTime, query);
        assertEquals(0, page.getTotal());
        assertEquals(List.of(), page.getRecords());
    }

    private void insert(long id, long userId, String type, String severity, int isRead, Instant createdAt) {
        jdbcTemplate.update("INSERT INTO t_notification VALUES (?, '通知', '内容', ?, ?)", id, type, severity);
        jdbcTemplate.update("INSERT INTO t_notification_recipient VALUES (?, ?, ?, ?, ?)",
                id, id, userId, isRead, Timestamp.from(createdAt));
    }
}
