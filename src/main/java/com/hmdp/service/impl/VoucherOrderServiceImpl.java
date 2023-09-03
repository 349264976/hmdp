package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.statement.execute.Execute;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private  StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Resource
    RedisIdWorker redisIdWork;
    /**
     * 加载lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT ;


    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VocherOrderHandler());
    }
//    private BlockingQueue<VoucherOrder> orderTasks=new ArrayBlockingQueue<>(1024*1024);
//
//    private class VocherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while (true){
//                //1.获取队列中得订单信息
//                try {
//                    VoucherOrder take = orderTasks.take();
//
//                    //2.创建订单
//                    handleVoucherOrder(take);
//                } catch (InterruptedException e) {
//                    log.error("处理订单异常");
//                    throw new RuntimeException(e);
//                }
//            }
//
//        }
//    }
private class VocherOrderHandler implements Runnable{
        String queueName="stream.orders";
    @Override
    public void run() {
        while (true){
            //1.获取队列中得订单信息
            try {
                //1.获取消息队列中得订单消息 XREADGROP GROP g1  c1 COUNT 1 BLOCK 2000 STREAMS streams。order
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"), StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2))
                        , StreamOffset.create(queueName, ReadOffset.lastConsumed())
                );
                //2.判断消息获取是否成功
                if (list==null || list.isEmpty()){
                    //如果获取失败说明没有消息 继续下一次循环
                    continue;
                }
                //2.1如果获取失败说明没有消息继续下一次循环

                //3.如果成功可以下单 解析消息中得订单信息
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object,Object> values = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                handleVoucherOrder(voucherOrder);
                //4.ACK确认SACK
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
                //2.创建订单
//                handleVoucherOrder(take);
//                getResult(voucherOrder);
            } catch (Exception e) {
                log.error("处理订单异常");
                handlePendingList();
                throw new RuntimeException(e);
            }
        }

    }


}


    private void handlePendingList() {
        String queueName="stream.orders";
            while (true) {
                //1.获取队列中得订单信息
                try {
                    //1.获取pending-list中得订单消息 XREADGROP GROP g1  c1 COUNT 1 BLOCK 2000 STREAMS streams。order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"), StreamReadOptions.empty().count(1)
                            , StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2.判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        //如果获取失败说明没有消息 jieshu循环
                        break;
                    }
                    //2.1如果获取失败说明没有消息继续下一次循环

                    //3.如果成功可以下单 解析消息中得订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    //4.ACK确认SACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                    //2.创建订单
                }catch (Exception e) {
                    log.error("Exception pending");
                }
            }

        }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {

        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //3.获取锁
        boolean isLock = lock.tryLock();
        //4.判断是否成功获取锁
        if (!isLock){
            // 获取锁失败，返回错误或者充实
            log.error("不允许重复下单");
            return;
        }

        ////获取代理对象
        try{
            proxy.getResult(voucherOrder);
        }catch (Exception e){

        }

        //释放锁


    }

    static {
        SECKILL_SCRIPT=new DefaultRedisScript<Long>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher voucher=seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            //秒杀尚未开始
//            return Result.fail("秒杀尚未开始");
//        }
//        //3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已结束");
//        }
//        //4.判断库存是否充足
//        if (voucher.getStock()<1){
//            return Result.fail("当前库存不足");
//        }
//        // 4-5 一人一单
//        Long userId =UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {
//        //创建锁对象
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//
//        //redisson锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//
//        //获取锁
//        boolean islock = lock.tryLock();
//
//        if (!islock){
//            //失败 获取锁失败
//            return Result.fail("不允许重复提交");
//        }
//
//        try{
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.getResult(voucherId,userId);
//        }finally {
//            //释放锁
//            lock.unlock();
//        }
//        //

//        }

//        方案2
        //1.0执行lua脚本
//        1.1获取用户
        Long userId=UserHolder.getUser().getId();
        long orderId=redisIdWork.nextId("order");
//        Long executeResult = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(), voucherId.toString(), userId.toString());

        Long executeResult = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );

        //2.0判断结果是0
        if (executeResult.intValue() != 0) {
            //2.1不为0代表没有资格购买，哎
            //2.1 不为0 嗲表没有购买资格
            return Result.fail(executeResult.intValue()==1?"库存不足":"不能重复购买");
        }
        //2.2为0 有购买资格把下单资格保存到阻塞队列
//        long orderId = redisIdWork.nextId("order");

        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1订单id
        //6.2用户id
//        Long userId= UserHolder.getUser().getId();
        //6.3代金券id
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        //2.3创建阻塞队列
//        orderTasks.add(voucherOrder);

        //3.获取代理对象
         proxy = (IVoucherOrderService)AopContext.currentProxy();
        //4.返回订单id
        //3.返回订单id
        return Result.ok(orderId);
    }
//    @Transactional
//    public Result getResult(Long voucherId,Long userId) {
//            //45.1查询订单
//            Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//            //45.2判断是否存在
//            if (count>0){
//                //用户已经有订单
//                return Result.fail("每人限购一单");
//            }
//            //5.扣减库存
//            boolean success = seckillVoucherService.update().setSql("stock=stock-1")
//                    .eq(true, "voucher_id", voucherId)
//                    .gt("stock",0).update();
//            //6.创建订单
//            if (!success){
//                return Result.fail("扣减库存失败");
//            }
//            VoucherOrder voucherOrder = new VoucherOrder();
//            //6.1订单id
//            long orderId = redisIdWork.nextId("order");
//            //6.2用户id
////        Long userId= UserHolder.getUser().getId();
//            //6.3代金券id
//            voucherOrder.setId(orderId);
//            voucherOrder.setUserId(userId);
//            voucherOrder.setVoucherId(voucherId);
//            save(voucherOrder);
//            //7.返回订单id
//            return Result.ok(orderId);
//    }


    @Transactional
    public void getResult(VoucherOrder voucherOrder) {

        //5.一人一旦
        Long userId = voucherOrder.getUserId();

        //5.1查询订单
        Integer count = query().eq("user_id", userId).eq("vouvher_id", voucherOrder.getVoucherId()).count();

        if (count > 0) {
            //用户已购买
            return;
        }
        //5.扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                .eq(true, "voucher_id", voucherOrder.getId())
                .gt("stock",0).update();
        //6.创建订单
        save(voucherOrder);
        //7.返回订单id
    }

}
