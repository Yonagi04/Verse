package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.messaging.MessageRequestValidation;
import com.yonagi.verse.common.util.*;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dto.resp.UserCostAlertSettingsRespDTO.ChannelReadiness;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class UserCostAlertChannelReadinessService {
    private final MessagingProperties properties;
    private final AesUtil aes;
    public List<ChannelReadiness> readiness(UserDO user) {
        return List.of(new ChannelReadiness("IN_APP",true,null,null),channel("SMS",user),channel("EMAIL",user));
    }
    public String contact(UserDO user,String channel) {
        String encrypted="SMS".equals(channel) ? user.getPhone() : user.getEmail();
        return encrypted==null || encrypted.isBlank() ? null : aes.decrypt(encrypted);
    }
    public String hash(UserDO user,String channel) { return "SMS".equals(channel) ? user.getPhoneHash() : user.getEmailHash(); }
    private ChannelReadiness channel(String channel,UserDO user) {
        try {
            String contact=contact(user,channel);
            boolean sms="SMS".equals(channel);
            String provider=sms ? properties.getSms().getProvider() : properties.getEmail().getProvider();
            String reason="disabled".equals(provider) ? "CHANNEL_DISABLED" : null;
            if (reason==null && (sms ? blank(properties.getSms().getPersonalCostWarningTemplateCode()) || blank(properties.getSms().getSignName())
                    : blank(properties.getEmail().getAccountName()))) reason="TEMPLATE_MISSING";
            if (reason==null && blank(contact)) reason="CONTACT_MISSING";
            if (reason==null && !(sms ? contact.matches("1[3-9][0-9]{9}") : MessageRequestValidation.email(contact))) reason="CONTACT_INVALID";
            String masked=blank(contact) ? null : sms ? SensitiveUtil.maskPhone(contact) : SensitiveUtil.maskEmail(contact);
            return new ChannelReadiness(channel,reason==null,reason,masked);
        } catch (RuntimeException failure) { return new ChannelReadiness(channel,false,"STATE_UNAVAILABLE",null); }
    }
    private boolean blank(String value) { return value==null || value.isBlank(); }
}
