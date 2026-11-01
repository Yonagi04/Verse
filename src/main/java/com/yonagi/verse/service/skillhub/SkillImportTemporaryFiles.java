package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仓库源字节使用受控随机临时文件，永不按远程文件路径创建本地文件。 */
@Component
@Slf4j
public class SkillImportTemporaryFiles {
    private static final long MIN_FREE_BYTES = 400L * 1024 * 1024;
    private static final long MAX_TEMP_BYTES = 400L * 1024 * 1024;
    private final Path directory = Path.of(System.getProperty("java.io.tmpdir"), "verse-skill-import").toAbsolutePath();
    private final java.util.Map<Path, Long> reservations = new java.util.HashMap<>();

    /** @return 调用方必须finally删除的随机文件；磁盘不足或残留过多安全失败 */
    public synchronized Path create(long maximumBytes) {
        try {
            if (maximumBytes <= 0 || maximumBytes > 100L * 1024 * 1024) {
                throw unavailable();
            }
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory) || Files.getFileStore(directory).getUsableSpace() < MIN_FREE_BYTES) {
                throw unavailable();
            }
            long bytes = reservations.values().stream().mapToLong(Long::longValue).sum();
            int count = 0;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "snapshot-*")) {
                for (Path file : files) {
                    if (!reservations.containsKey(file.toAbsolutePath())) {
                        bytes += Files.size(file);
                    }
                    if (++count > 1000 || bytes + maximumBytes > MAX_TEMP_BYTES) {
                        throw unavailable();
                    }
                }
            }
            if (bytes + maximumBytes > MAX_TEMP_BYTES) {
                throw unavailable();
            }
            Path file = Files.createTempFile(directory, "snapshot-", ".tmp");
            reservations.put(file.toAbsolutePath(), maximumBytes);
            return file;
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    /** 不跨目录，不跟随远程或未知路径；删除失败留给有界过期清理。 */
    public synchronized void remove(Path path) {
        if (path == null || !directory.equals(path.toAbsolutePath().getParent())
                || !path.getFileName().toString().startsWith("snapshot-")) {
            return;
        }
        try {
            Files.deleteIfExists(path);
            reservations.remove(path.toAbsolutePath());
        } catch (IOException exception) {
            log.warn("Skill临时源文件删除失败: type={}", exception.getClass().getSimpleName());
        }
    }

    /** 执行最多120秒；20分钟前遗留文件不可能仍属于有效执行代次。 */
    @Scheduled(fixedDelay = 60000L)
    public void removeExpired() {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Instant cutoff = Instant.now().minusSeconds(1200);
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "snapshot-*")) {
            for (Path file : files) {
                if (++count > 1000) {
                    break;
                }
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && !Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isAfter(cutoff)) {
                    remove(file);
                }
            }
        } catch (IOException exception) {
            log.warn("Skill临时源清理失败: type={}", exception.getClass().getSimpleName());
        }
    }

    private ServerException unavailable() {
        return new ServerException("Skill临时磁盘不可用或超过容量限制", SkillErrorCodeEnum.UNAVAILABLE);
    }
}
