package com.yonagi.verse.service.budget;
import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import com.yonagi.verse.dto.resp.CostNotificationReadinessRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
@Service @RequiredArgsConstructor
public class CostNotificationReadinessService {
    private final TenantAccessPolicy config;
    private final TenantMapper budgets;
    private final MessagingProperties properties;
    private final BudgetPeriodResolver time;
    private boolean present(String value) { return value != null && !value.isBlank(); }
    public CostNotificationReadinessRespDTO readiness(Long userId, Long tenantId) {
        return readiness(userId,tenantId,false);
    }
    public CostNotificationReadinessRespDTO tenantReadiness(Long userId, Long tenantId) {
        return readiness(userId,tenantId,true);
    }
    private CostNotificationReadinessRespDTO readiness(Long userId, Long tenantId, boolean tenant) {
        config.requireAdmin(userId,tenantId);
        boolean sms=!"disabled".equals(properties.getSms().getProvider());
        boolean templates=present(tenant ? properties.getSms().getTenantCostWarningTemplateCode() : properties.getSms().getCostWarningTemplateCode()) && present(tenant ? properties.getSms().getTenantCostLimitTemplateCode() : properties.getSms().getCostLimitTemplateCode()) && present(properties.getSms().getSignName());
        boolean email=!"disabled".equals(properties.getEmail().getProvider()) && present(properties.getEmail().getAccountName());
        return new CostNotificationReadinessRespDTO(List.of(new CostNotificationReadinessRespDTO.Channel("SMS",sms && templates,
                !sms ? "CHANNEL_DISABLED" : !templates ? "TEMPLATE_MISSING" : null),
                new CostNotificationReadinessRespDTO.Channel("EMAIL",email,email ? null : "CHANNEL_NOT_CONFIGURED")),
                budgets.administratorContacts(tenantId),time.iso(time.local(time.now())));
    }
}
