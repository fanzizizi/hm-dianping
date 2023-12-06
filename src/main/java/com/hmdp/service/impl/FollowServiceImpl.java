package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    private IBlogService blogService;

    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (isFollow) {
            //关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean save = save(follow);
            if (save) {
                String key = "follows:" + userId;
                stringRedisTemplate.opsForSet().add(key, String.valueOf(id));
            }
        } else {
            //取关
            remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", id));

            String key = "follows:" + userId;
            stringRedisTemplate.opsForSet().remove(key, String.valueOf(id));

        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        int count = query().eq("user_id", UserHolder.getUser().getId())
                .eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }

    @Override
    public Result getCommonFollow(Long targetId) {
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + userId;
        String key2 = "follows:" + targetId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null) return Result.ok(Collections.emptyList());
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        List<UserDTO> collect = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(collect);
    }

}
