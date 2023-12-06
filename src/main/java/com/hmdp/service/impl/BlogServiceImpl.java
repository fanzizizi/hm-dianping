package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> ISLIKE_SCRIPT;

    static {
        ISLIKE_SCRIPT = new DefaultRedisScript<>();
        ISLIKE_SCRIPT.setLocation(new ClassPathResource("islike.lua"));
        ISLIKE_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = this.getById(id);
        if (blog == null) {
            return Result.fail("博客不存在");
        }
        queryBlogUser(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(this::queryBlogUser);
        return Result.ok(records);
    }

    @Override
    public Result queryFollowBlog(Long lastId, Integer offset) {
        //1.查询收件箱
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.FEED_KEY + userId;
//        stringRedisTemplate.opsForZSet().rangeByScore(key, lastId, 0, offset, 3);
        //reverseRangeByScoreWithScores和reverseRangeByScore的区别：
        //第一个有score的值，第二个没有，只有元素
        Set<ZSetOperations.TypedTuple<String>> set = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);
        if (set == null || set.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(set.size());
        long min = Long.MAX_VALUE;
        int count = 0;
        //遍历sorted中的数据，然后找到offset，ids....
        for (ZSetOperations.TypedTuple<String> tuple : set) {
            ids.add(Long.valueOf(Objects.requireNonNull(tuple.getValue())));
            if (tuple.getScore().longValue() < min) {
                min = tuple.getScore().longValue();
                count = 1;
            } else {
                count++;
            }
        }
        //根据ids查询blog数据库
        String s = StrUtil.join(",", ids);
        List<Blog> blogs = this.query().in("id", ids)
                .last("order by field(id," + s + ")").list();
        for (Blog blog : blogs) {
            queryBlogUser(blog);
        }
        //封装返回数据
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setOffset(count);
        result.setMinTime(min);
        return Result.ok(result);
    }

    @Override
    public Result likeBlog(Long id) {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();

        Long result = stringRedisTemplate.execute(ISLIKE_SCRIPT,
                Collections.emptyList(), id.toString(),
                userId.toString(), String.valueOf(System.currentTimeMillis()));
        assert (result != null);
        int val = result.intValue();
        if (val != 0) {
            this.update()
                    .setSql("liked = liked - 1").eq("id", id).update();
            return Result.ok("取消点赞成功");
        } else {
            this.update()
                    .setSql("liked = liked + 1").eq("id", id).update();
            return Result.ok("点赞成功");
        }
    }

    @Override
    public Result queryLikes(Long id) {
        Set<String> top5 = stringRedisTemplate.opsForZSet().range("blog:liked:" + id, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
//        List<User> users = userService.listByIds(ids);
        //这里查询后，顺序改变，原因是数据库的问题，
        List<User> users = userService.query()
                .in("id", ids).last("order by field(id," + idStr + ")").list();
        ArrayList<UserDTO> res = new ArrayList<>(5);
        for (User user : users) {
            res.add(BeanUtil.copyProperties(user, UserDTO.class));
        }
        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean save = this.save(blog);
        if (!save) {
            return Result.fail("新增失败");
        }
        //查询粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        if (follows == null || follows.isEmpty()) {
            return Result.ok("没有粉丝");
        }
        for (Follow follow : follows) {
            String key = "feed:" + follow.getUserId();
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(),
                    System.currentTimeMillis());
        }
        //推送
        return Result.ok(blog.getId());
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
//        blog.setIsLike(Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
//                .isMember("blog:liked:" + blog.getId(), UserHolder.getUser().getId().toString())));
        Long id = UserHolder.getUser().getId();
        if (id == null) return;
        blog.setIsLike(stringRedisTemplate.opsForZSet()
                .score("blog:liked:" + blog.getId(), id.toString()) != null);
    }
}
