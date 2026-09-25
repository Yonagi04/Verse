package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按显式绑定选择唯一的适配器，杜绝隐式协议回退。 */
@Component
public class AdapterRegistry {
    private final List<AdapterRegistration> registrations;

    public AdapterRegistry(List<AdapterRegistration> registrations) {
        this.registrations = List.copyOf(registrations);
    }

    public AdapterRegistration select(String provider, ModelOperation operation, UpstreamProtocol protocol) {
        if (protocol == null || operation == null) throw unsupported();
        List<AdapterRegistration> matches = registrations.stream().filter(candidate ->
                candidate.operation() == operation && candidate.protocol() == protocol
                        && (protocol == UpstreamProtocol.OPENAI_COMPAT
                        ? candidate.provider() == null
                        : provider != null && provider.equalsIgnoreCase(candidate.provider()))).toList();
        if (matches.size() != 1) throw unsupported();
        return matches.get(0);
    }

    private ClientException unsupported() {
        return new ClientException(LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
    }
}
