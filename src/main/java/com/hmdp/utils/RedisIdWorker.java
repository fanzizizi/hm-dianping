package com.hmdp.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/*
   @Date 2023/11/25-15:23
   @author fff
*/
@Component
public class RedisIdWorker {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final int COUNT_BITS = 32;

    private static final long BEGIN_TIMESTAMP = 1672531200L;


    public long nextId(String keyPrefix) {
//        LocalDateTime now = LocalDateTime.now();
//        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
//        long timestamp = nowSecond - BEGIN_TIMESTAMP;
//        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
//        long count = redisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
//        return timestamp << COUNT_BITS | count;
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        long count = redisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}
