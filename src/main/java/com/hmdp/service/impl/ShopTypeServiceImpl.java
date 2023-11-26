package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result queryTypeList() {
        String key = "TypeList";
        String typeList = redisTemplate.opsForValue().get(key);
        if (!StringUtils.isEmpty(typeList)) {
            List<ShopType> shopTypeList = JSONUtil.toList(typeList, ShopType.class);
            System.out.println("redis结束");
            return Result.ok(shopTypeList);
        }
        //如果redis没有
        List<ShopType> shopTypeList = this.query().orderByAsc("sort").list();
        //存入redis
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList));
        return Result.ok(shopTypeList);
    }

//    public Result queryTypeList2() {
//        String key = "TypeList";
//        redisTemplate.opsForList().
//    }
}
