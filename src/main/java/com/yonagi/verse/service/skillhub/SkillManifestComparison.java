package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dto.resp.SkillFileChangeRespDTO;
import com.yonagi.verse.dto.resp.SkillFileRespDTO;
import com.yonagi.verse.service.skillhub.port.SkillValidatedFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 有界文件分类，压缩字节和包外层目录不参与内容比较。 */
public final class SkillManifestComparison {
    private SkillManifestComparison() {}

    /**
     * @return 相对路径有序的完整分类；null仅表示合法空基准
     */
    public static List<SkillFileChangeRespDTO> compare(
            SkillPackageManifest oldManifest, SkillPackageManifest newManifest) {
        Map<String, SkillFileRespDTO> oldFiles = index(oldManifest);
        Map<String, SkillFileRespDTO> newFiles = index(newManifest);
        TreeSet<String> paths = new TreeSet<>(oldFiles.keySet());
        paths.addAll(newFiles.keySet());
        return paths.stream()
                .map(
                        path -> {
                            SkillFileRespDTO oldFile = oldFiles.get(path);
                            SkillFileRespDTO newFile = newFiles.get(path);
                            String type =
                                    oldFile == null
                                            ? "ADDED"
                                            : newFile == null
                                                    ? "DELETED"
                                                    : oldFile.sha256().equals(newFile.sha256())
                                                            ? "UNCHANGED"
                                                            : "MODIFIED";
                            return new SkillFileChangeRespDTO(path, type, oldFile, newFile);
                        })
                .toList();
    }

    private static Map<String, SkillFileRespDTO> index(SkillPackageManifest manifest) {
        Map<String, SkillFileRespDTO> result = new HashMap<>();
        if (manifest == null) {
            return result;
        }
        if (manifest.packageName() == null
                || manifest.packageName().isBlank()
                || manifest.files() == null
                || manifest.files().size() > SkillPackageLimitConstant.MAX_FILE_COUNT) {
            throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
        }
        for (SkillValidatedFile file : manifest.files()) {
            if (file == null
                    || file.relativePath() == null
                    || file.sha256() == null
                    || file.sha256().length() != 64
                    || file.sizeBytes() < 0
                    || result.putIfAbsent(
                                    file.relativePath(),
                                    new SkillFileRespDTO(
                                            file.relativePath(), file.sizeBytes(), file.sha256()))
                            != null) {
                throw new ServerException(SkillErrorCodeEnum.UNAVAILABLE);
            }
        }
        return result;
    }
}
