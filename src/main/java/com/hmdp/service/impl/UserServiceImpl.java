package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import io.lettuce.core.BitFieldArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //不符合，返回错误信息
            return Result.fail("电话号码格式错误");
        }
        //符合生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码
        //session.setAttribute("code", code);
        //设置验证码有效时长2分钟
        redisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone, code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // TODO 发送验证码
        log.debug("发生短信验证码{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("电话号码格式错误");
        }
        String code = loginForm.getCode();
        String password = loginForm.getPassword();
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = redisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        User user = this.getOne(new QueryWrapper<User>().eq("phone", phone));

        if (!StringUtils.isEmpty(code)) {
            //验证码登录情况
            if (cacheCode == null || !cacheCode.equals(code)) {
                return Result.fail("验证码错误");
            }

        } else if (!StringUtils.isEmpty(password)) {
            //密码登录情况
            if (user == null) {
                return Result.fail("还未注册用户，怎么可能有密码");
            }
            if (!password.equals(user.getPassword())) {
                return Result.fail("密码或用户名错误输入错误");
            }
        } else {
            Result.fail("请输入密码或验证码");
        }

        if (user == null) {
            //用户未注册情况下
            user = this.createUserWithPhone(phone);
            this.save(user);
        }

        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> map = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        redisTemplate.opsForHash().putAll(
                RedisConstants.LOGIN_USER_KEY + token, map);

        redisTemplate.expire(
                RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    //签到功能
    @Override
    public Result sign() {

        LocalDateTime date = LocalDateTime.now();

        String keySuffix = date.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = RedisConstants.USER_SIGN_KEY + UserHolder.getUser().getId() + keySuffix;

        int offset = date.getDayOfMonth() - 1;

        redisTemplate.opsForValue().setBit(key, offset, true);
        return Result.ok();
    }

    @Override
    public Result signContinuous() {
        //组装key
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        String key = RedisConstants.USER_SIGN_KEY + UserHolder.getUser().getId() + keySuffix;
        //今天日期
        int dayOfMonth = now.getDayOfMonth();

        List<Long> res = redisTemplate.opsForValue()
                .bitField(
                        key,
                        BitFieldSubCommands.create()
                                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                                .valueAt(0)
                );
        if (res == null || res.isEmpty()) {
            return Result.ok(0);
        }
        //根据res的值，用位运算统计签到天数
        Long num = res.get(0);
        if (num == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                break;
            } else {
                count++;
                num >>>= 1;//逻辑右移，高位补0
            }
        }
        return Result.ok(count);
    }


    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        return user;
    }
}
