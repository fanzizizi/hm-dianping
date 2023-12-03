package com.hmdp;

import lombok.extern.java.Log;
import lombok.extern.log4j.Log4j;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;
import org.redisson.RedissonMultiLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/*
   @Date 2023/12/1-19:06
   @author fff
*/
@Log4j2
@SpringBootTest
public class TestRedisson {

    @Resource
    RedissonClient redissonClient;

    @Test
    void method1() throws InterruptedException {
        RLock lock = redissonClient.getLock("order");
        boolean isLock = lock.tryLock(1L, TimeUnit.SECONDS);
//        lock.tryLock();

        if (!isLock) {
            log.error("获取锁失败");
        }
        try {
            log.info("获取锁成功");
            method2();
            log.info("执行业务");
        } finally {
            lock.unlock();
        }
    }

    @Test
    public void method2() {
        System.out.println(Thread.currentThread().getId());
    }
}
