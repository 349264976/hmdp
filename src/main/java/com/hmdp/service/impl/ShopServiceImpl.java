package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;
    @Override
    public Result queryByid(Long id) {

        //缓存穿透
//        Shop shop = queryWithPassThrough(id);
        //缓存穿透
//       Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        //逻辑过期来解决缓存击穿问题
//        Shop shop=queryWithLogicalExpire(id);

        //工具类使用缓存击穿
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if (shop == null) {

            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }
//
//    @Override
//    public Result queryByid(Long id) {
//        //1.从redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);
//        //2.判断是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            //3.存在直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return  Result.ok(shop);
//        }
//        //判断命中的是否是空值
//        if(shopJson != null) {
//            //返回一个错误信息
//            return Result.fail("店铺信息不存在");
//        }
//        //4.不存在根据id查询数据库
//        Shop shop=getById(id);
//        //5.不存在返回错误
//        if (shop == null) {
//            //将空值写入到redis
//            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+ id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return Result.fail("Shop not found");
//        }
//        //6.存在写入到redis
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
//        return Result.ok(shop);
//    }
    //缓存穿透
    public Shop queryWithPassThrough(Long id){
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在直接返回
            return  JSONUtil.toBean(shopJson, Shop.class);

        }
        //判断命中的是否是空值
        if(shopJson != null) {
            //返回一个错误信息
           return null;
        }
        //4.不存在根据id查询数据库
        Shop shop=getById(id);
        //5.不存在返回错误
        if (shop == null) {
            //将空值写入到redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+ id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6.存在写入到redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
        return shop;
    }

    public Shop queryWithMutex(Long id) {
        String locKey="lock:shop"+id;
        Shop shop=null;
        try {//1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在直接返回
            return  JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值
        if(shopJson != null) {
            //返回一个错误信息
            return null;
        }
        //4.不存在根据id查询数据库
        //4.1获取互斥锁

        boolean isLock=tryLock(locKey);
        //4.2判断是否成功
        if (!isLock){
            //4.3失败则休眠并重试
                Thread.sleep(50);
            return queryWithMutex(id);
        }
        //4.4成功根据id查询数据库
      shop=getById(id);
        //5.不存在返回错误
        if (shop == null) {
            //将空值写入到redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+ id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6.存在写入到redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),30L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unlock(locKey);
        }
        return shop;
    }
    private  static final ExecutorService CACHE_REBUILD_EXCUTOR= Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.存在直接返回
            return  null;
        }
        //3.命中 存在判断一下是否过期
        RedisData bean = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop1= JSONUtil.toBean((JSONObject) bean.getData(),Shop.class);
        LocalDateTime expiretime=bean.getExpireTime();
        //4.先把redis反序列化为对象

        //5.判断过期时间
        if (expiretime.isAfter(LocalDateTime.now())){
            //5.1.未过期直接返回数据
            return  shop1;
        }
        //5.2已过期
        //6.0缓存重建
        String lockKey=LOCK_SHOP_KEY +id;
        boolean isLock=tryLock(lockKey);
        //6.1获取互斥锁
        if (isLock){
            //6.2判断是否获取锁成功
        try {
            //6.3成功开启独立线程实现缓存重建
            CACHE_REBUILD_EXCUTOR.submit(()->{
                //重建缓存
                this.saveShoptoRedis(id,30L);
            });
        }catch (Exception e){
        throw  new RuntimeException(e);
        }finally {
            //释放锁
            unlock(lockKey);
        }
        }
        //6.4失败 返回过期信息
        return shop1;
    }




    private boolean tryLock(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }
    private void unlock(String key){
            stringRedisTemplate.delete(key);
    }

    public void saveShoptoRedis(Long key,Long expireSecondes){
        //1.查询店铺数据数据
        Shop shop=getById(key);

        //2.封装成逻辑过期时间
        RedisData redisData=new RedisData();
        //3.写入到Redis
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecondes));

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+key,JSONUtil.toJsonStr(redisData));
    }
    @Override
    @Transactional
    public Result updateShop(Shop shop) {

        //1.更新数据库
        updateById(shop);

        if (shop.getId() ==null){
            return Result.fail("Shop not found");
        }
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
}
