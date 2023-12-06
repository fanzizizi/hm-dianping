package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
//        Shop shop = cacheClient.getWithThrough(CACHE_SHOP_KEY, id,
//                Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        Shop shop = cacheClient.getWithLogicalExpire(CACHE_SHOP_KEY,
//                id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);
        Shop shop = cacheClient.getWithMutex(CACHE_SHOP_KEY,
                id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("获取店铺失败");
        }
        return Result.ok(shop);
    }


    //版本2：互斥锁还解决了缓冲击穿问题
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //redis
        String shopJson = redisTemplate.opsForValue().get(key);
        if (!StringUtils.isEmpty(shopJson) || Objects.equals(shopJson, "")) {
            System.out.println("redis返回");
            if (shopJson.equals("")) {
                return null;
            }
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //redis不存在查询mysql
        //1.实现缓存重建，获取互斥锁，判断是否获取成功
        //2.如果失败，休眠重试
        Shop shop;
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {

                Thread.sleep(50);

                return queryWithMutex(id);
            }
            //3.成功查询数据库写入redis，释放互斥锁锁
            shop = this.getById(id);
            //模拟并发;
//            Thread.sleep(200);
            if (shop == null) {
                redisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //mysql存在，存入redis
            redisTemplate.opsForValue().set(key,
                    JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean aBoolean = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(aBoolean);
    }

    private void unlock(String key) {
        redisTemplate.delete(key);
    }

    //新线程存储信息到redis
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //1.查询mysql
        Thread.sleep(200);
        Shop shop = getById(id);
        //2.封装为redisData，或者也可以在原类上添加逻辑过期字段，不过比较麻烦
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.存入redis
        redisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        this.updateById(shop);
        //2.删除redis缓存
        redisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            Page<Shop> page = this.query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        String key = "shop:geo:" + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()//等价于WITHDISTANCE
                        .limit(end));//等价于查询end个记录(从0开始)
        if (results == null || results.getContent().isEmpty()) return Result.ok("没有下一页");

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() <= from) return Result.ok();
        //从0到end个记录，截取其中的 form 到 end；
        ArrayList<Long> ids = new ArrayList<>(content.size());
        HashMap<String, Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(from).forEach(result -> {
            //店铺id和距离
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            Distance distance = result.getDistance();
            distanceMap.put(shopId, distance);
        });
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = this.query().in("id", ids).last("order by field(id," + idStr + ")").list();
        shops.forEach(shop -> {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        });
        return Result.ok(shops);
    }

    //版本1：只解决了缓冲穿透的问题,使用封装工具类实现
//    public Shop queryWithPassThrough(Long id) {
//
//        String key = CACHE_SHOP_KEY + id;
//        //redis
//        String shopJson = redisTemplate.opsForValue().get(key);
//        if (!StringUtils.isEmpty(shopJson) || Objects.equals(shopJson, "")) {
//            System.out.println("redis返回");
//            if (shopJson.equals("")) {
//                return null;
//            }
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        //redis不存在查询mysql
//        //mysql还不存在返回错误信息
//        Shop shop = this.getById(id);
//        if (shop == null) {
//            redisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        //mysql存在，存入redis
//        redisTemplate.opsForValue().set(key,
//                JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return shop;
//    }

    //版本三：使用逻辑过期的方式解决缓冲击穿
    //该版本默认redis不存在的数据mysql一定不存在，所以需要把热点key手动加进redis，
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//
//    public Shop queryWithLogicalExpire(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        //redis不存在
//        String redisDataJson = redisTemplate.opsForValue().get(key);
//        if (StringUtils.isEmpty(redisDataJson)) {
//            return null;
//        }
//        //redis存在，判断逻辑过期是否过期
//        RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
//        JSONObject data = (JSONObject) redisData.getData();
//        LocalDateTime expireTime = redisData.getExpireTime();
//        Shop shop = JSONUtil.toBean(data, Shop.class);
//
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            //未过期返回
//            return shop;
//        }
//        //过期，请求锁，如果没有获得锁，返回旧数据
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        if (isLock) {
//            //获取锁成功
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    saveShop2Redis(id, 20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    unlock(lockKey);
//                }
//            });
//        }
//        //获取锁失败,返回旧数据
//        return shop;
//    }
}
