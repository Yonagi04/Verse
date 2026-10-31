package com.yonagi.verse.service.user;

import com.yonagi.verse.dao.mapper.AccountAvailabilityMapper;
import com.yonagi.verse.dto.resp.UserPublicProfileRespDTO;
import com.yonagi.verse.service.AvatarService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** 用户域批量公开资料边界；实时读昵称/状态，不缓存账号授权。 */
@Service
@RequiredArgsConstructor
public class UserPublicProfileService {
    private static final int MAX_IDS = 100;
    private final AccountAvailabilityMapper mapper;
    private final AvatarService avatars;

    /**
     * @param userIds 调用方本页用到的去重账号，不允许全库查询
     * @return 仅公开展示资料；不存在的账号以匿名资料代替
     */
    public Map<Long, UserPublicProfileRespDTO> read(Collection<Long> userIds) {
        if (userIds == null || userIds.size() > MAX_IDS || userIds.stream().anyMatch(id -> id == null || id < 1)) {
            throw new IllegalArgumentException("公开资料批次必须包含不超过100个有效账号");
        }
        Map<Long, UserPublicProfileRespDTO> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        for (Long id : userIds) {
            result.put(id, new UserPublicProfileRespDTO(id.toString(), "已注销用户", null));
        }
        for (var row : mapper.selectPublicProfiles(userIds.stream().distinct().toList())) {
            result.put(row.getUserId(), new UserPublicProfileRespDTO(row.getUserId().toString(),
                    row.getDisplayName(), avatars.publicUrl(row.getAvatar())));
        }
        return result;
    }
}
