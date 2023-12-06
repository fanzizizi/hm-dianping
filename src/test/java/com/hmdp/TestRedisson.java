package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/*
   @Date 2023/12/1-19:06
   @author fff
*/
@Log4j2
@SpringBootTest
public class TestRedisson {

    @Resource
    RedissonClient redissonClient;

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    IShopService shopService;

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

    @Test
    void loadShopData() {
        List<Shop> list = shopService.list();
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            List<Shop> value = entry.getValue();
            String key = "shop:geo:" + entry.getKey();
            value.forEach(shop -> {
                        stringRedisTemplate.opsForGeo().add(key
                                , new Point(shop.getX(), shop.getY()), shop.getId().toString());
                    }
            );
//            redisTemplate.opsForGeo().add(key, )
        }

    }

}
