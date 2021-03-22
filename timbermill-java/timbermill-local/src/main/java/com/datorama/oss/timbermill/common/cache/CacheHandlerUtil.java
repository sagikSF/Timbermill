package com.datorama.oss.timbermill.common.cache;

import com.datorama.oss.timbermill.LocalCacheConfig;
import com.datorama.oss.timbermill.common.redis.RedisServiceConfig;

public class CacheHandlerUtil {
    public static AbstractCacheHandler getCacheHandler(String strategy, LocalCacheConfig localCacheConfig, RedisServiceConfig redisServiceConfig) {

        if (strategy.compareToIgnoreCase("redis") == 0){
            return new RedisCacheHandler(redisServiceConfig);
        }
        else {
            return new LocalCacheHandler(localCacheConfig);
        }
    }
}
