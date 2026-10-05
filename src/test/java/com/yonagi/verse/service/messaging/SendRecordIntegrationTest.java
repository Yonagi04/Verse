package com.yonagi.verse.service.messaging;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.yonagi.verse.async.messaging.*;
import com.yonagi.verse.common.config.*;
import com.yonagi.verse.common.convention.exception.MessageSendException;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.common.messaging.provider.*;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SendRecordIntegrationTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private SendRecordStore records;
    private DataSourceTransactionManager transactions;
    private SmsSendRecordMapper smsMapper;
    private EmailSendRecordMapper emailMapper;
    private AesUtil aes;
    private final MessagingProperties properties = new MessagingProperties();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final VerificationCodeStore codes = mock(VerificationCodeStore.class);
    private final SmsProvider sms = mock(SmsProvider.class);
    private final EmailProvider email = mock(EmailProvider.class);

    @BeforeEach void setUp() throws Exception {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(database); jdbc.execute("SET MODE MySQL");
        try (var connection = database.getConnection()) {
            for (String script : List.of("V20261005_01__message_send_records.sql", "V20261005_02__async_message_delivery.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + script));
            }
        }
        var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SmsSendRecordMapper.class); configuration.addMapper(EmailSendRecordMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(database); factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        transactions = new DataSourceTransactionManager(database);
        String key = Base64.getEncoder().encodeToString(new byte[32]); aes = new AesUtil(key, key);
        smsMapper = session.getMapper(SmsSendRecordMapper.class); emailMapper = session.getMapper(EmailSendRecordMapper.class);
        records = new SendRecordStore(smsMapper, emailMapper, aes, transactions);
        when(sms.name()).thenReturn("fixture"); when(email.name()).thenReturn("fixture");
        when(sms.send(any())).thenReturn(MessageSubmissionRespDTO.accepted("request", "message"));
        when(email.send(any())).thenReturn(MessageSubmissionRespDTO.accepted("request", "env"));
    }
    @AfterEach void close() { database.shutdown(); metrics.close(); }
    private SmsSendReqDTO request(String id, String code) { return new SmsSendReqDTO(id, "RESET", 1L, null, "Verse", "13800138000", "SMS_1", Map.of("code", code)); }
    private SmsSendReqDTO request() { return request("same-id", "123456"); }
    private SmsSendService service() { return new SmsSendService(sms, records, metrics, properties); }
    private MessageDeliveryWorker worker() { return new MessageDeliveryWorker(records, sms, email, codes, properties, metrics); }
    private long id() { return Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM t_sms_send_record WHERE request_id='same-id'", Long.class)); }
    private String status() { return jdbc.queryForObject("SELECT status FROM t_sms_send_record WHERE request_id='same-id'", String.class); }
    private void dueAgain() { jdbc.update("UPDATE t_sms_send_record SET next_attempt_at=?", new Date(1)); }

    @Test void commitsEncryptedQueuedTaskBeforeExternalCallAndReusesResults() {
        assertEquals(MessageSubmissionStatus.QUEUED, service().send(request()).status());
        assertEquals(MessageSubmissionStatus.QUEUED, service().send(request()).status()); verify(sms, never()).send(any());
        var row = jdbc.queryForMap("SELECT * FROM t_sms_send_record");
        assertNotNull(row.get("PAYLOAD_ENCRYPTED")); assertFalse(row.toString().contains("123456")); assertFalse(row.toString().contains("13800138000"));
        when(sms.send(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); assertEquals("SUBMITTING", status());
            assertEquals(request(), call.getArgument(0));
            return MessageSubmissionRespDTO.accepted("r", "m");
        });
        worker().execute(MessageChannel.SMS, id());
        assertEquals("ACCEPTED", status()); assertNull(jdbc.queryForMap("SELECT * FROM t_sms_send_record").get("PAYLOAD_ENCRYPTED"));
        assertEquals("m", service().send(request()).providerMessageId()); verify(sms, times(1)).send(any());
        var conflict = assertThrows(MessageSendException.class, () -> service().send(request("same-id", "999999")));
        assertEquals(MessagingErrorCode.REQUEST_CONFLICT.code(), conflict.getErrorCode());
    }

    @Test void concurrentSubmissionsAndWorkersHaveSingleInvocation() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1); var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 8; i++) jobs.add(pool.submit(() -> { start.await(); return service().send(request()); }));
            start.countDown(); for (var job : jobs) job.get(5, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_sms_send_record", Integer.class));
            jobs.clear(); long id = id();
            for (int i = 0; i < 8; i++) jobs.add(pool.submit(() -> worker().execute(MessageChannel.SMS, id)));
            for (var job : jobs) job.get(5, TimeUnit.SECONDS);
            assertEquals("ACCEPTED", status()); verify(sms, times(1)).send(any());
        }
    }

    @Test void submissionReturnsWhileProviderBlockedAndEmailHasSeparatePool() throws Exception {
        properties.setMaxConcurrency(1); properties.getAsync().setQueueCapacity(1);
        var config = new MessageDeliveryExecutorConfiguration();
        ThreadPoolTaskExecutor smsPool = config.smsDeliveryExecutor(properties), emailPool = config.emailDeliveryExecutor(properties);
        smsPool.initialize(); emailPool.initialize();
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1); var mailed = new CountDownLatch(1);
        when(sms.send(any())).thenAnswer(call -> {
            assertTrue(Thread.currentThread().getName().startsWith("verse-sms-send-")); entered.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS)); return MessageSubmissionRespDTO.accepted("r", "m");
        });
        when(email.send(any())).thenAnswer(call -> { mailed.countDown(); return MessageSubmissionRespDTO.accepted("r", "env"); });
        try {
            service().send(request()); service().send(request("second", "222222"));
            var dispatcher = new MessageDeliveryDispatcher(records, worker(), smsPool, emailPool, properties, metrics);
            dispatcher.dispatch(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTimeout(Duration.ofSeconds(2), () -> assertEquals(MessageSubmissionStatus.QUEUED, service().send(request("third", "333333")).status()));
            new EmailSendService(email, records, metrics, properties).send(new EmailSendReqDTO("same-id", "ALERT", 1L, 2L, "user@example.com", "private subject", "private body", null));
            dispatcher.dispatch(); assertTrue(mailed.await(5, TimeUnit.SECONDS));
            assertEquals(1, smsPool.getThreadPoolExecutor().getQueue().size());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, smsPool.getThreadPoolExecutor().getRejectedExecutionHandler());
            verify(sms, times(1)).send(any());
        } finally { finish.countDown(); smsPool.shutdown(); emailPool.shutdown(); }
        assertEquals("QUEUED", jdbc.queryForObject("SELECT status FROM t_sms_send_record WHERE request_id='third'", String.class));
        assertFalse(jdbc.queryForMap("SELECT * FROM t_email_send_record").toString().contains("private"));
    }

    @Test void rejectsTransactionDatabaseFailureAndBacklogBeforeExternalCalls() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var error = assertThrows(MessageSendException.class, () -> service().send(request()));
            assertEquals(MessagingErrorCode.TRANSACTION_ACTIVE.code(), error.getErrorCode());
        });
        properties.getAsync().setBacklogLimit(1); service().send(request());
        assertEquals(MessageSubmissionStatus.QUEUED, service().send(request()).status());
        var full = assertThrows(MessageSendException.class, () -> service().send(request("second", "222222")));
        assertEquals(MessagingErrorCode.BUSY.code(), full.getErrorCode()); assertFalse(full.mayHaveBeenSubmitted());
        jdbc.execute("DROP TABLE t_sms_send_record");
        assertFalse(assertThrows(MessageSendException.class, () -> service().send(request())).mayHaveBeenSubmitted()); verify(sms, never()).send(any());
    }

    @Test void restoresQueuedTasksAfterRestartButNeverResendsAbandonedExecution() {
        service().send(request());
        var restarted = new SendRecordStore(smsMapper, emailMapper, aes, transactions);
        assertEquals(List.of(id()), restarted.due(MessageChannel.SMS, 10));
        var old = restarted.claim(MessageChannel.SMS, id()).orElseThrow();
        assertTrue(restarted.claim(MessageChannel.SMS, id()).isEmpty());
        jdbc.update("UPDATE t_sms_send_record SET update_time=?", new Date(1));
        assertEquals(1, restarted.recoverAbandoned(MessageChannel.SMS, new Date()));
        assertEquals("UNKNOWN", status()); assertNull(jdbc.queryForMap("SELECT * FROM t_sms_send_record").get("PAYLOAD_ENCRYPTED"));
        assertThrows(IllegalStateException.class, () -> restarted.complete(old, MessageSubmissionRespDTO.accepted("r", "m"), 1));
        worker().execute(MessageChannel.SMS, id()); verify(sms, never()).send(any());
        service().send(request("restart-queued", "222222"));
        long remaining = Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM t_sms_send_record WHERE request_id='restart-queued'", Long.class));
        new MessageDeliveryWorker(restarted, sms, email, codes, properties, metrics).execute(MessageChannel.SMS, remaining);
        verify(sms).send(request("restart-queued", "222222"));
    }

    @Test void lostCommitAcknowledgmentDoesNotClaimThatTaskWasNeverQueued() {
        var uncertainManager = spy(transactions);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("commit acknowledgment lost"); })
                .when(uncertainManager).commit(any());
        var uncertain = new SendRecordStore(smsMapper, emailMapper, aes, uncertainManager);
        var sender = new SmsSendService(sms, uncertain, metrics, properties);
        var failure = assertThrows(MessageSendException.class, () -> sender.send(request()));
        assertTrue(failure.mayHaveBeenSubmitted()); assertEquals("QUEUED", status());
        worker().execute(MessageChannel.SMS, id()); assertEquals("ACCEPTED", status()); verify(sms).send(request());
    }

    @Test void lostRetryAcknowledgmentKeepsVerificationForPossiblyQueuedRetry() {
        var verification = new VerificationCodeStore.Reservation("code", "rate", "backup", "owner", "123456", "same-id");
        when(codes.isCurrent(any())).thenReturn(true); when(sms.send(any())).thenReturn(MessageSubmissionRespDTO.retryable("CONNECT_FAILED"));
        service().send(request(), verification);
        var uncertain = spy(records);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("retry acknowledgment lost"); })
                .when(uncertain).retry(any(), any(), anyLong(), any());
        new MessageDeliveryWorker(uncertain, sms, email, codes, properties, metrics).execute(MessageChannel.SMS, id());
        assertEquals("QUEUED", status()); verify(codes, never()).restore(any());
        when(sms.send(any())).thenReturn(MessageSubmissionRespDTO.accepted("r", "m")); dueAgain(); worker().execute(MessageChannel.SMS, id());
        assertEquals("ACCEPTED", status()); verify(sms, times(2)).send(request()); verify(codes, never()).restore(any());
    }

    @Test void encryptionFailureIsDefinitelyNotQueuedForEitherChannel() {
        var failedEncryption = spy(aes);
        doThrow(new IllegalStateException("encryption unavailable")).when(failedEncryption).encrypt(anyString());
        var failedStore = new SendRecordStore(smsMapper, emailMapper, failedEncryption, transactions);
        var smsFailure = assertThrows(MessageSendException.class, () -> failedStore.enqueue(request(), "fixture", null, properties.getAsync()));
        assertFalse(smsFailure.mayHaveBeenSubmitted()); assertEquals(MessagingErrorCode.RECORD_FAILED.code(), smsFailure.getErrorCode());
        var emailRequest = new EmailSendReqDTO("mail", "ALERT", 1L, null, "user@example.com", "subject", "body", null);
        var emailFailure = assertThrows(MessageSendException.class, () -> failedStore.enqueue(emailRequest, "fixture", properties.getAsync()));
        assertFalse(emailFailure.mayHaveBeenSubmitted());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_sms_send_record", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_email_send_record", Integer.class));
        verify(sms, never()).send(any()); verify(email, never()).send(any());
    }

    @Test void expiryDuringVerificationPreflightDoesNotStartProviderCall() {
        properties.getAsync().setMaxTaskAge(Duration.ofSeconds(1));
        var verification = new VerificationCodeStore.Reservation("code", "rate", "backup", "owner", "123456", "same-id");
        service().send(request(), verification);
        when(codes.isCurrent(any())).thenAnswer(call -> { Thread.sleep(1200); return true; });
        worker().execute(MessageChannel.SMS, id());
        assertEquals("EXPIRED", status()); verify(codes).isCurrent(verification);
        verify(sms, never()).send(any()); verify(codes).restore(verification);
    }

    @Test void retriesOnlyExplicitPreSubmissionFailuresWithBackoffAndAttemptBound() {
        when(sms.send(any())).thenReturn(MessageSubmissionRespDTO.retryable("CONNECT_FAILED"));
        service().send(request());
        worker().execute(MessageChannel.SMS, id()); assertEquals("QUEUED", status());
        Date next = jdbc.queryForObject("SELECT next_attempt_at FROM t_sms_send_record", Date.class); assertNotNull(next); assertTrue(next.after(new Date()));
        worker().execute(MessageChannel.SMS, id()); verify(sms, times(1)).send(any());
        for (int i = 1; i < properties.getAsync().getMaxAttempts(); i++) { dueAgain(); worker().execute(MessageChannel.SMS, id()); }
        assertEquals("REJECTED", status()); verify(sms, times(3)).send(request());
        assertNull(jdbc.queryForMap("SELECT * FROM t_sms_send_record").get("PAYLOAD_ENCRYPTED"));
    }

    @Test void unknownAndTerminalPersistenceFailureNeverTriggerRetries() {
        when(sms.send(any())).thenReturn(MessageSubmissionRespDTO.unknown("TIMEOUT")); service().send(request());
        worker().execute(MessageChannel.SMS, id()); worker().execute(MessageChannel.SMS, id());
        assertEquals("UNKNOWN", status()); verify(sms, times(1)).send(any());
        jdbc.execute("DELETE FROM t_sms_send_record"); clearInvocations(sms);
        when(sms.send(any())).thenReturn(MessageSubmissionRespDTO.accepted("r", "m")); service().send(request());
        var failing = spy(records); doThrow(new IllegalStateException("fixture")).when(failing).complete(any(), any(), anyLong());
        new MessageDeliveryWorker(failing, sms, email, codes, properties, metrics).execute(MessageChannel.SMS, id());
        assertEquals("SUBMITTING", status()); worker().execute(MessageChannel.SMS, id()); verify(sms, times(1)).send(any());
        assertEquals(MessageSubmissionStatus.UNKNOWN, service().send(request()).status());
    }

    @Test void expirationAndConsumedCodeDoNotSendAndDefiniteRejectionRestores() {
        var verification = new VerificationCodeStore.Reservation("code", "rate", "backup", "owner", "123456", "same-id");
        service().send(request(), verification); jdbc.update("UPDATE t_sms_send_record SET deadline_at=?", new Date(1));
        worker().execute(MessageChannel.SMS, id()); assertEquals("EXPIRED", status()); verify(sms, never()).send(any()); verify(codes).restore(verification);
        jdbc.execute("DELETE FROM t_sms_send_record"); clearInvocations(codes);
        service().send(request(), verification); when(codes.isCurrent(any())).thenReturn(false);
        worker().execute(MessageChannel.SMS, id()); assertEquals("EXPIRED", status()); verify(sms, never()).send(any());
        jdbc.execute("DELETE FROM t_sms_send_record"); clearInvocations(codes);
        when(codes.isCurrent(any())).thenReturn(true); when(sms.send(any())).thenReturn(MessageSubmissionRespDTO.rejected("DENIED"));
        service().send(request(), verification); worker().execute(MessageChannel.SMS, id());
        assertEquals("REJECTED", status()); verify(codes).restore(verification);
        assertNull(jdbc.queryForMap("SELECT * FROM t_sms_send_record").get("PAYLOAD_ENCRYPTED"));
    }

    @Test void verificationRedisFailureRetriesWithoutProviderOrNewCode() {
        var verification = new VerificationCodeStore.Reservation("code", "rate", "backup", "owner", "123456", "same-id");
        service().send(request(), verification);
        when(codes.isCurrent(any())).thenThrow(new MessageSendException(MessagingErrorCode.STATE_FAILED, false));
        worker().execute(MessageChannel.SMS, id()); assertEquals("QUEUED", status()); verify(codes, never()).restore(any());
        for (int i = 1; i < 3; i++) { dueAgain(); worker().execute(MessageChannel.SMS, id()); }
        assertEquals("REJECTED", status()); verify(codes).restore(verification); verify(sms, never()).send(any());
    }

    @Test void changedProviderAndCorruptPayloadAreRejectedBeforeSending() {
        service().send(request()); when(sms.name()).thenReturn("different");
        worker().execute(MessageChannel.SMS, id()); assertEquals("REJECTED", status()); verify(sms, never()).send(any());
        jdbc.execute("DELETE FROM t_sms_send_record"); when(sms.name()).thenReturn("fixture"); service().send(request());
        jdbc.update("UPDATE t_sms_send_record SET payload_encrypted='invalid'");
        worker().execute(MessageChannel.SMS, id()); assertEquals("REJECTED", status()); verify(sms, never()).send(any());
    }
}
