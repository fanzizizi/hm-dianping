package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/*
   @Date 2023/11/24-12:11
   @author fff
*/
public class LoginInterceptor implements HandlerInterceptor {


    private StringRedisTemplate redisTemplate;

    public LoginInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        HttpSession session = request.getSession();
//        UserDTO user = (UserDTO) session.getAttribute("user");
//        String token = request.getHeader("authorization");
//
//        if (StringUtils.isEmpty(token)) {
//            response.setStatus(401);
//            return false;
//        }
//        Map<Object, Object> userMap = redisTemplate.opsForHash()
//                .entries(RedisConstants.LOGIN_USER_KEY + token);
//        if (userMap.isEmpty()) {
//            response.setStatus(401);
//            return false;
//        }
//        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//        UserHolder.saveUser(user);
//        //重置token有效期
//        redisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token,
//                RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        //之前的操作在之前拦截器完成
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        HandlerInterceptor.super.postHandle(request, response, handler, modelAndView);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);
    }
}
