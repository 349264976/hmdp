package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
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
    @Override
    public Result follow(Long followIdUserId, boolean isFollow) {

        Long id = UserHolder.getUser().getId();
        String key="follows:"+id;
        //1.判断到底是关注还是取关
        if (isFollow) {
            //2.关注，新增数据
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followIdUserId);
            boolean save = save(follow);
            if (save){
                //关注成功放入到redis的set集合
                stringRedisTemplate.opsForSet().add(key,followIdUserId.toString());
            }
        }else {
            //3.取关
            boolean remove = remove(new QueryWrapper<Follow>()
                    .eq("user_id", id)
                    .eq("follow_user_id", followIdUserId)
            );
            if (remove){
                //吧关注的用户从Redis集合中益处
                stringRedisTemplate.opsForSet().remove(key,followIdUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1.查询是否关注
        Long id = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", id)
                .eq("follow_user_id", followUserId).count();
        if (count>0){
            return  Result.ok(true);
        }
        return Result.ok(false);
    }

    @Override
    public Result getCommons(Integer id) {
        //1.获取当前用户
        Long currentUser = UserHolder.getUser().getId();
        //2.求交集
        String currentkey="follows"+currentUser;

        String targetkey="follow"+id;

        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(currentkey, targetkey);

        if (intersect==null || intersect.isEmpty()){
            return Result.ok(Collections.emptySet());
        }

        //3.解析出id集合
        List<Long> commonId = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //4.查询用户
        List<UserDTO> userDTOS = userService.listByIds(commonId).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
