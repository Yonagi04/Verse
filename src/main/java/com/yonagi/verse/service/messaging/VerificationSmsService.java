package com.yonagi.verse.service.messaging;

import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.MessageSendException;
import com.yonagi.verse.common.enums.MessagingErrorCode;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dto.req.SmsSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class VerificationSmsService {
    private final SmsSendService sender;
    private final VerificationCodeStore codes;
    private final MessagingProperties properties;
    private final AesUtil aes;
    private final SecureRandom random = new SecureRandom();
    public VerificationSmsService(SmsSendService sender, VerificationCodeStore codes, MessagingProperties properties, AesUtil aes) {
        this.sender = sender; this.codes = codes; this.properties = properties; this.aes = aes;
    }

    public boolean sendPasswordReset(String phone) {
        String hash = aes.hashForLookup(phone);
        return send(phone, null, "PASSWORD_RESET", properties.getSms().getResetTemplateCode(),
                RedisKeyConstant.USER_PHONE_SENDING_CODE_KEY + hash,
                RedisKeyConstant.USER_PHONE_SENDING_CODE_KEY + "rate:" + hash, hash);
    }
    public boolean sendAccountClosure(Long userId, String phone) {
        return send(phone, userId, "ACCOUNT_CLOSURE", properties.getSms().getClosureTemplateCode(),
                RedisKeyConstant.USER_CLOSE_ACCOUNT_SENDING_CODE_KEY + userId,
                RedisKeyConstant.USER_CLOSE_ACCOUNT_SENDING_CODE_KEY + "rate:" + userId, aes.hashForLookup(phone));
    }

    private boolean send(String phone, Long userId, String scene, String template, String codeKey, String rateKey, String phoneHash) {
        String requestId = UUID.randomUUID().toString();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        var request = new SmsSendReqDTO(requestId, scene, userId, null, properties.getSms().getSignName(), phone, template, Map.of("code", code));
        var reservation = codes.reserve(codeKey, rateKey, phoneHash, code, requestId, properties.getSms().getDailyLimit());
        MessageSubmissionRespDTO result;
        try {
            result = sender.send(request, reservation);
        } catch (MessageSendException failure) {
            if (!failure.mayHaveBeenSubmitted()) codes.restore(reservation);
            throw failure;
        }
        if (result.status() == MessageSubmissionStatus.QUEUED || result.status() == MessageSubmissionStatus.ACCEPTED) return true;
        if (result.status() == MessageSubmissionStatus.REJECTED) {
            codes.restore(reservation);
            throw new MessageSendException(MessagingErrorCode.REJECTED, false);
        }
        throw new MessageSendException(MessagingErrorCode.UNKNOWN, true);
    }
}
