package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/*
   @Date 2023/11/26-21:48
   @author fff
*/
public class SimpleRedisLock implements ILock {

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PRE_FIX = UUID.randomUUID().toString(true) + "-";

    private final StringRedisTemplate redisTemplate;
    private final String name;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate redisTemplate, String name) {
        this.redisTemplate = redisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PRE_FIX + Thread.currentThread().getId();
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,
                threadId + "", timeoutSec, TimeUnit.SECONDS));
    }

    //lua版本释放锁
    @Override
    public void unlock() {
        //如果线程阻塞，此时锁已经被超时释放，那么可能发生锁误删
        //所以这里，判断是不是该线程的锁，是的情况释放锁
        //lua脚本实现
        redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PRE_FIX + Thread.currentThread().getId());
//        redisTemplate.execute(UNLOCK_SCRIPT,
//                Collections.singletonList(KEY_PREFIX + name),
//                Collections.singletonList(ID_PRE_FIX + Thread.currentThread().getId()));
    }

    //普通版本释放锁，存在问题：如果
//    @Override
//    public void unlock() {
//        //如果线程阻塞，此时锁已经被超时释放，那么可能发生锁误删
//        //所以这里，判断是不是该线程的锁，是的情况释放锁
//        String threadId = ID_PRE_FIX + Thread.currentThread().getId();
//        String id = redisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (Objects.equals(id, threadId)) {
    //存在问题：如果在此时，线程阻塞，redis分布式锁释放，另一线程获得锁。
    //那么下面的del将删除 另一线程 的锁
//            redisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
