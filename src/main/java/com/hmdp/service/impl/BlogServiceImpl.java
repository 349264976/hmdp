package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
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
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import java.sql.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
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
    private static final String SECKILL_STOCK_KEY="blog:liked:";

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.extracted(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result querBlogById(Long id) {
        //1.查询blog
        Blog blog =getById(id);
        if (blog==null){
            return Result.fail("笔记不存在");
        }
        //2.查询blog有关的用户
        extracted(blog);
        //3.查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        if (userId==null){
            return;
        }
        //1.判断当前登录用户是否点赞
        String key="blog:liked:"+blog.getId();
        Double ismember = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(ismember!=null);
    }

    @Override
    public Result likeBlog(Long id) {

        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //1.判断当前登录用户是否点赞
        String key="blog:liked:"+id;
        Double ismember = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (ismember==null){
            //3.如果为点赞可以点赞

            //3.1数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked+1").eq("id", id).update();
            //3.2保存到用户Redis得set集合
            if (isSuccess){
            stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }

        }else {
            //4.如果已经点赞 取消点赞
            boolean isSuccess = update().setSql("liked = liked-1").eq("id", id).update();
            //4.1数据库点赞数-1

            //4.2把用户从Redis的set集合移除
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }


    @Override
    public Result queryBlogLikes(Long id) {
        //1.查询top5的点赞用户Zrange Key 0  4
            String key=SECKILL_STOCK_KEY+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5==null|| top5.isEmpty()){
            return Result.ok();
        }
        //2.解析出其中的用户id
        List<Long> collect = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //3.根据用户id查询用户
        String idStr= StrUtil.join(",",collect);
        //4.返回数据
        List<UserDTO> collect1 = userService.query().in("id",collect).last("ORDER BY FIELD(id,"+idStr+")").list().stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(collect1);
    }
    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean save = save(blog);
        if (!save){
            return Result.fail("新增失败");
        }
        //3.查寻笔记做着的所有粉丝 select * from tb——follow where follow_user_id=?
        List<Follow> followUser = followService.query().eq("follow_user_id", user.getId()).list();
        //4.推送笔记id给所有粉丝
        for (Follow follow : followUser) {
            //4.1获取粉丝id
            Long userId = follow.getUserId();
            //4.2推送
            String key ="feed:"+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }

        //5.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFlollow(Long max, Integer offset) {

        //1.获取当前用户 查询收件箱
        Long userId = UserHolder.getUser().getId();

        //2.查询收件箱  ZREVRANGEBYSCORE key Max Min LIMIT offset count
       String key = FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key,0,max,offset,2);

        if (typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        ArrayList<Long> ids = new ArrayList<Long>(typedTuples.size());
        //3.解析数据 blogId  score（时间戳）
        long mintime=0;
        int offsetnum = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple :typedTuples) {
            //4.1获取id
            String value = typedTuple.getValue();
            ids.add(Long.valueOf(value));
            long time=typedTuple.getScore().longValue();
            //4.2获取分数      //4.3获取时间戳
            if (time==mintime){
                offsetnum++;
            }else {
                mintime=time;
                offsetnum=1;
            }

        }
        String idStr = StrUtil.join(",", ids);

        //4.根据id查询blog
        List<Blog> blogs = query().in("id",ids).last("ORDER BY FIELD(id,"+idStr+")").list();

        for (Blog blog : blogs){
            //2.查询blog有关的用户
            extracted(blog);
            //3.查询blog是否被点赞
            isBlogLiked(blog);
        }

        //5.封装并且返回
        ScrollResult scrollResult=new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(offsetnum);
        scrollResult.setMinTime(mintime);
        return Result.ok(blogs);
    }

    private void extracted(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
