package com.yonagi.verse.common.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 布隆过滤器存量数据初始化器
 * 应用启动时自动将数据库中已有的 username 和 phone 写入布隆过滤器
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description 解决数据库存量数据未写入布隆过滤器的问题
 * @date 2026/07/19 10:30
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(1)
public class BloomFilterInitializer implements CommandLineRunner {

    private static final String INIT_FLAG_KEY = "bloom_filter:initialized";

    private final UserMapper userMapper;
    private final RBloomFilter<String> usernameBloomFilter;
    private final RBloomFilter<String> phoneBloomFilter;
    private final RedissonClient redissonClient;

    @Override
    public void run(String... args) {
        if (redissonClient.getBucket(INIT_FLAG_KEY).isExists()) {
            log.info("布隆过滤器已完成初始化，跳过同步");
            return;
        }

        log.info("开始将存量用户数据同步到布隆过滤器...");
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
                if (user.getUsername() != null) {
                    usernameBloomFilter.add(user.getUsername());
                }
                if (user.getPhone() != null) {
                    phoneBloomFilter.add(user.getPhone());
                }
            }

            totalSynced += users.size();
            offset += batchSize;

            if (users.size() < batchSize) {
                break;
            }
        }

        redissonClient.getBucket(INIT_FLAG_KEY).set("1");

        log.info("布隆过滤器存量数据同步完成，共同步 {} 条，耗时 {}ms",
                totalSynced, System.currentTimeMillis() - startTime);
    }
}
