package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;
import org.springframework.context.annotation.Primary;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** 按服务的显式能力绑定分派上游协议；路由失败时绝不尝试其他协议。 */
@Primary
@Component
public class RoutingProviderAdapter implements ProviderAdapter, MediaOperationAdapter {
    private final AdapterRegistry registry;

    public RoutingProviderAdapter(AdapterRegistry registry) {
        this.registry = registry;
    }

    private ProviderAdapter select(ForwardContext context) {
        ModelOperation operation = context.getOperation() == null
                ? ModelOperation.CHAT_COMPLETIONS : context.getOperation();
        UpstreamProtocol protocol = context.getProtocol() == null
                ? UpstreamProtocol.OPENAI_COMPAT : context.getProtocol();
        AdapterRegistration registration = registry.select(context.getProvider(), operation, protocol);
        if (!(registration instanceof ProviderAdapter adapter) || adapter == this) {
            throw new com.yonagi.verse.common.convention.exception.ClientException(
                    com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
        }
        return adapter;
    }

    @Override
    public String forward(ForwardContext context) {
        return select(context).forward(context);
    }

    @Override
    public Flux<ServerSentEvent<String>> stream(ForwardContext context) {
        return select(context).stream(context);
    }

    @Override
    public AdapterExchange.Result invoke(ForwardContext context, AdapterExchange.Request request) {
        ProviderAdapter adapter = select(context);
        if (!(adapter instanceof MediaOperationAdapter mediaAdapter)) {
            throw new com.yonagi.verse.common.convention.exception.ClientException(
                    com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum.CAPABILITY_UNSUPPORTED);
        }
        return mediaAdapter.invoke(context, request);
    }
}
