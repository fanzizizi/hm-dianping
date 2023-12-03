package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
