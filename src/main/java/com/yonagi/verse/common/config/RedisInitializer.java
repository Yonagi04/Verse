package com.yonagi.verse.common.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存量数据初始化器
 * 应用启动时自动将数据库中已有的 username/phoneHash 写入布隆过滤器，并将 emailHash→userId 映射写入 Redis Set
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description 解决数据库存量数据未写入缓存的问题
 * @date 2026/07/19 10:30
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(1)
public class RedisInitializer implements CommandLineRunner {

    private static final String BLOOM_INIT_FLAG_KEY = "bloom_filter:initialized";
    private static final String EMAIL_SET_INIT_FLAG_KEY = "email_set:initialized";

    private final UserMapper userMapper;
    private final RBloomFilter<String> usernameBloomFilter;
    private final RBloomFilter<String> phoneBloomFilter;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(String... args) {
        boolean bloomInitialized = redissonClient.getBucket(BLOOM_INIT_FLAG_KEY).isExists();
        boolean emailSetInitialized = redissonClient.getBucket(EMAIL_SET_INIT_FLAG_KEY).isExists();

        if (bloomInitialized && emailSetInitialized) {
            log.info("存量数据已完成初始化，跳过同步");
            return;
        }

        log.info("开始将存量用户数据同步到缓存...");
        long startTime = System.currentTimeMillis();

        int batchSize = 5000;
        long offset = 0;
        int totalSynced = 0;

        while (true) {
            List<UserDO> users = userMapper.selectList(
                    Wrappers.lambdaQuery(UserDO.class)
                            .eq(UserDO::getDelFlag, 0)
                            .last("LIMIT " + offset + ", " + batchSize)
            );

            if (users.isEmpty()) {
                break;
            }

            for (UserDO user : users) {
                if (!bloomInitialized) {
                    if (user.getUsername() != null) {
                        usernameBloomFilter.add(user.getUsername());
                    }
                    if (user.getPhoneHash() != null) {
                        phoneBloomFilter.add(user.getPhoneHash());
                    }
                }

                if (!emailSetInitialized && user.getEmailHash() != null) {
                    stringRedisTemplate.opsForSet().add(
                            RedisKeyConstant.USER_EMAIL_COUNT_KEY + user.getEmailHash(),
                            user.getUserId().toString());
                }
            }

            totalSynced += users.size();
            offset += batchSize;

            if (users.size() < batchSize) {
                break;
            }
        }

        if (!bloomInitialized) {
            redissonClient.getBucket(BLOOM_INIT_FLAG_KEY).set("1");
        }
        if (!emailSetInitialized) {
            redissonClient.getBucket(EMAIL_SET_INIT_FLAG_KEY).set("1");
        }

        log.info("存量数据同步完成，共同步 {} 条，布隆过滤器={}, 邮箱SET={}, 耗时 {}ms",
                totalSynced, !bloomInitialized, !emailSetInitialized,
                System.currentTimeMillis() - startTime);
    }
}
