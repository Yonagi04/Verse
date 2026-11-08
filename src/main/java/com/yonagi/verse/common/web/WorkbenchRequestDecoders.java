package com.yonagi.verse.common.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.yonagi.verse.common.util.JsonNumbers;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ConfigDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.LaneDTO;
import com.yonagi.verse.dto.playground.WorkbenchTypes.RunConfigDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.CreateReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.ForkReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.RestoreReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.RoundReqDTO;
import com.yonagi.verse.dto.req.WorkbenchRequestsDTO.UpdateReqDTO;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.yonagi.verse.service.playground.PlaygroundConfiguration.invalid;

/** 统一保留请求数字精度并拒绝 Jackson 默认的跨类型强制转换。 */
public final class WorkbenchRequestDecoders {
    private static final ObjectReader TREE_READER = new ObjectMapper().reader()
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private WorkbenchRequestDecoders() {
    }

    /** 将保留原数值精度的 JSON 对象转换为具名命令。 */
    public abstract static class AbstractDecoder<T> extends JsonDeserializer<T> {
        @Override
        public final T deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return command(TREE_READER.readTree(parser));
        }

        /** 绑定此命令的允许字段；非法配置抛出领域错误。 */
        protected abstract T command(JsonNode node);
    }

    /** 创建只接受标题、描述和可编辑配置。 */
    public static final class Create extends AbstractDecoder<CreateReqDTO> {
        @Override
        protected CreateReqDTO command(JsonNode node) {
            fields(node, Set.of("title", "description", "config"));
            return new CreateReqDTO(text(node.get("title")), text(node.get("description")), config(node.get("config")));
        }
    }

    /** 更新保留实际字段集合，区分缺失与显式 null。 */
    public static final class Update extends AbstractDecoder<UpdateReqDTO> {
        @Override
        protected UpdateReqDTO command(JsonNode node) {
            Set<String> supplied = fields(node, Set.of("title", "description", "config", "revision"));
            BigDecimal revision = number(node.get("revision"));
            if (supplied.contains("revision") && !JsonNumbers.isPositiveInteger(revision, Integer.MAX_VALUE)) {
                throw invalid();
            }
            return new UpdateReqDTO(text(node.get("title")), text(node.get("description")),
                    config(node.get("config")), revision, supplied);
        }
    }

    /** 分叉只接受来源标识，历史由服务端构建。 */
    public static final class Fork extends AbstractDecoder<ForkReqDTO> {
        @Override
        protected ForkReqDTO command(JsonNode node) {
            fields(node, Set.of("attemptId", "laneId", "before", "config", "title"));
            if (node.has("before") && !node.get("before").isBoolean()) {
                throw invalid();
            }
            return new ForkReqDTO(text(node.get("attemptId")), text(node.get("laneId")),
                    node.has("before") ? node.get("before").booleanValue() : null,
                    config(node.get("config")), text(node.get("title")));
        }
    }

    /** 启动轮次只接受字符串问题。 */
    public static final class Round extends AbstractDecoder<RoundReqDTO> {
        @Override
        protected RoundReqDTO command(JsonNode node) {
            fields(node, Set.of("prompt"));
            if (!node.path("prompt").isTextual()) {
                throw invalid();
            }
            return new RoundReqDTO(node.get("prompt").textValue());
        }
    }

    /** 恢复版本拒绝非整数、溢出和字符串。 */
    public static final class Restore extends AbstractDecoder<RestoreReqDTO> {
        @Override
        protected RestoreReqDTO command(JsonNode node) {
            fields(node, Set.of("version"));
            BigDecimal version = number(node.get("version"));
            if (!JsonNumbers.isPositiveInteger(version, Integer.MAX_VALUE)) {
                throw invalid();
            }
            return new RestoreReqDTO(version);
        }
    }

    private static Set<String> fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        if (!allowed.containsAll(names)) {
            throw invalid();
        }
        return Set.copyOf(names);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid();
        }
        return node.textValue();
    }

    private static BigDecimal number(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw invalid();
        }
        return node.decimalValue();
    }

    private static ConfigDTO config(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        fields(node, Set.of("lanes", "synced", "presetId", "presetVersion"));
        JsonNode lanes = node.path("lanes");
        if (!lanes.isArray() || lanes.isEmpty() || lanes.size() > 3) {
            throw invalid();
        }
        List<LaneDTO> result = new ArrayList<>();
        for (JsonNode lane : lanes) {
            fields(lane, Set.of("laneId", "serviceId", "config"));
            result.add(new LaneDTO(text(lane.get("laneId")), text(lane.get("serviceId")), run(lane.get("config"))));
        }
        JsonNode synced = node.get("synced");
        if (synced != null && !synced.isNull() && !synced.isBoolean()) {
            throw invalid();
        }
        JsonNode version = node.get("presetVersion");
        return new ConfigDTO(result, synced == null || synced.isNull() || synced.booleanValue(),
                node.path("presetId").isTextual() ? node.get("presetId").textValue() : null,
                version != null && version.isNumber() ? version.decimalValue() : null);
    }

    private static RunConfigDTO run(JsonNode node) {
        if (node == null || node.isNull()) {
            return new RunConfigDTO();
        }
        fields(node, Set.of("system", "temperature", "topP", "maxTokens"));
        return new RunConfigDTO(text(node.get("system")), number(node.get("temperature")),
                number(node.get("topP")), number(node.get("maxTokens")));
    }
}
