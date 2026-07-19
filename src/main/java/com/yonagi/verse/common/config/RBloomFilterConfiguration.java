package com.yonagi.verse.common.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/19 09:55
 */
@Slf4j
@Configuration
public class RBloomFilterConfiguration {

    @Bean
    public RBloomFilter<String> usernameBloomFilter(RedissonClient redissonClient) {
        RBloomFilter<String> usernameBloomFilter = redissonClient.getBloomFilter("usernameBloomFilter");
        if (!usernameBloomFilter.tryInit(1000000L, 0.001)) {
            log.warn("usernameBloomFilter already exists, existing parameters will be used");
        }
        return usernameBloomFilter;
    }

    @Bean
    public RBloomFilter<String> phoneBloomFilter(RedissonClient redissonClient) {
        RBloomFilter<String> phoneBloomFilter = redissonClient.getBloomFilter("phoneBloomFilter");
        if (!phoneBloomFilter.tryInit(1000000L, 0.001)) {
            log.warn("phoneBloomFilter already exists, existing parameters will be used");
        }
        return phoneBloomFilter;
    }
}
