package com.hmdp.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/*
   @Date 2023/11/25-12:49
   @author fff
*/
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate redisTemplate;

    public CacheClient(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLoginExpire(String key, Object value, Long expire, TimeUnit timeUnit) {
        //封装成redisData对象
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expire)));
        //存入redis
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R getWithThrough(String keyPrefix, ID id, Class<R> type
            , Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        //得到redisKey
        String key = keyPrefix + id;
        String objectJson = redisTemplate.opsForValue().get(key);
        //得到val，判断不为空
        if (!StringUtils.isEmpty(objectJson)) {
            return JSONUtil.toBean(objectJson, type);
        }
        //防止缓冲穿透，给mysql和redis都不存在的数据，放入了“”空字符串
        if (Objects.equals(objectJson, "")) {
            return null;
        }
        //查询mysql，使用回调函数
        R r = dbFallback.apply(id);
        //防止缓存穿透
        if (r == null) {
            this.set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, ttl, unit);
        return r;
    }

    //版本2：互斥锁还解决了缓冲击穿问题,优点：一致性好，但可用性差
    public <R, ID> R getWithMutex(String keyPreFix, ID id, Class<R> type,
                                  Function<ID, R> dbFallBack, Long ttl, TimeUnit unit) {
        String key = keyPreFix + id;
        //redis
        String shopJson = redisTemplate.opsForValue().get(key);
        if (!StringUtils.isEmpty(shopJson) || Objects.equals(shopJson, "")) {
            if (shopJson.equals("")) {
                return null;
            }
            return JSONUtil.toBean(shopJson, type);
        }
        //redis不存在查询mysql
        //1.实现缓存重建，获取互斥锁，判断是否获取成功
        //2.如果失败，休眠重试
        R r;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                Thread.sleep(50);
                return getWithMutex(keyPreFix, id, type, dbFallBack, ttl, unit);
            }
            //3.成功查询数据库写入redis，释放互斥锁锁
            r = dbFallBack.apply(id);
            if (r == null) {
                redisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //mysql存在，存入redis
            this.set(key, r, ttl, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return r;
    }

    //版本三：使用逻辑过期的方式解决缓冲击穿  优点：可用性好，但一致性差
    //该版本默认redis不存在的数据mysql一定不存在，所以需要把热点key手动加进redis，
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R getWithLogicalExpire(String keyPreFix, ID id, Class<R> type,
                                          Function<ID, R> dbFallBack, Long ttl, TimeUnit unit) {
        String key = keyPreFix + id;
        //redis不存在
        String redisDataJson = redisTemplate.opsForValue().get(key);
        if (StringUtils.isEmpty(redisDataJson)) {
            return null;
        }
        //redis存在，判断逻辑过期是否过期
        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean(data, type);

        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期返回
            return r;
        }
        //过期，请求锁，如果没有获得锁，返回旧数据
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            //获取锁成功,开启独立线程，完成数据更新，而该线程返回旧数据，退出
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R newR = dbFallBack.apply(id);
                    setWithLoginExpire(key, newR, ttl, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        //返回旧数据
        return r;
    }

    private boolean tryLock(String key) {
        Boolean aBoolean = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(aBoolean);
    }

    private void unlock(String key) {
        redisTemplate.delete(key);
    }


}
