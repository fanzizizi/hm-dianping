package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
   @Date 2023/11/29-21:33
   @author fff
*/
@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient redissonClient() {
        //注意包名 Redisson包下的
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.200.130:6379").setPassword("123456");
        return Redisson.create(config);
    }
}
