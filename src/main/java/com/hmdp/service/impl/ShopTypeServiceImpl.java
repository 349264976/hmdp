package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private RedisTemplate redisTemplate;
    @Override
//    @Cacheable(value = CACHE_SHOPTYPE_KEY,key = "typeList")
    public Result getShopTypeList() {
        //1.从redis中查询数据库
        List<ShopType> TypeList = redisTemplate.opsForList().range(CACHE_SHOPTYPE_KEY, 0, -1);
        //2.判断是否有元素
        if (TypeList.size() != 0) {
            //3.不存在查询数据库存在直接返回
            return Result.ok(TypeList);
        }
        //4.不存在查询数据库
        List<ShopType> shoptypeList = query().orderByAsc("sort").list();
       //5.不存在返回错误
        if (shoptypeList.size()==0){
            return Result.fail("Please select is null");
        }
        //6.存在写入到redis注解实现
        redisTemplate.opsForList().rightPushAll(CACHE_SHOPTYPE_KEY, shoptypeList);
        return Result.ok(shoptypeList);
    }
}
