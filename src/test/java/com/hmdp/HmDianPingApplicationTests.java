package com.hmdp;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HmDianPingApplicationTests {
@Autowired
   private ShopServiceImpl service;
@Autowired
private RedisIdWorker redisIdWorker;

private ExecutorService es= Executors.newFixedThreadPool(500);
@Test
public void testWork() throws InterruptedException {
    service.saveShoptoRedis(1L,15L);
    CountDownLatch latch=new CountDownLatch(300);
    Runnable task=()->{
        for (int i=0;i<100;i++){
          long id = redisIdWorker.nextId("order");
            System.out.println("id=:"+id);
        }
            latch.countDown();
    };
        long begin= System.currentTimeMillis();
        for (int i = 0; i <300; i++) {
        es.submit(task);
        }
        latch.await();
        long end= System.currentTimeMillis();
    System.out.println("time:="+(end-begin));

}
}
