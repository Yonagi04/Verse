package com.yonagi.verse.common.cache;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 通用缓存不得因新增领域权限而依赖业务实现或业务表名单。 */
class QueryCacheArchitectureTest {
    @Test void cacheInfrastructureDoesNotImportBusinessServicesOrPersistence() throws Exception {
        try (var files = Files.list(Path.of("src/main/java/com/yonagi/verse/common/cache"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("import com.yonagi.verse.service."), file.toString());
                assertFalse(source.contains("import com.yonagi.verse.dao."), file.toString());
                assertFalse(source.contains("import com.yonagi.verse.common.enums."), file.toString());
            }
        }
        String catalogue = Files.readString(Path.of("src/main/java/com/yonagi/verse/common/cache/QueryCatalogue.java"));
        assertFalse(catalogue.contains("enum Access"));
        assertFalse(catalogue.contains("\"t_api_key\""));
        assertFalse(catalogue.contains("\"t_notification\""));
    }
}
