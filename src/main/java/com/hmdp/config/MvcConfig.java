package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/*
   @Date 2023/11/24-12:18
   @author fff
*/
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //拦截器顺序根据配置顺序，也可以通过order配置顺序
        registry.addInterceptor(new RefreshTokenInterceptor(redisTemplate)).order(0);

        registry.addInterceptor(new LoginInterceptor(redisTemplate)).
                excludePathPatterns("/user/code", "/user/login",
                        "/blog/hot", "/shop/**", "/shop-type/**",
                        "/upload/**", "/voucher/**").order(5);
//        WebMvcConfigurer.super.addInterceptors(registry);
    }
}
