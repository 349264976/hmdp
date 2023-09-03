package com.hmdp.utils;
import cn.hutool.core.lang.UUID;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class SimpleRedisLock implements  ILock{
    private StringRedisTemplate stringRedisTemplate;
    private static final String key_prefix = "lock:";

    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT =new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    private String lockName;

    public SimpleRedisLock(String lockName,StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.lockName = lockName;
    }

    @Override
    public boolean trylock(long timeoutSec) {

        //value最好待上线程id
        String threadId = ID_PREFIX+Thread.currentThread().getId();

        //获取锁操作
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key_prefix + lockName, threadId, timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(aBoolean);
    }

//    @Override
//    public void unlock() {
//        //释放锁
//
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
//        //获取线程标识
//        String id = stringRedisTemplate.opsForValue().get(key_prefix + lockName);
//        if (threadId.equals(id)) {
//            stringRedisTemplate.delete(key_prefix+lockName);
//        }
//    }

    @Override
    public void unlock() {
        //使用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key_prefix + lockName),ID_PREFIX+Thread.currentThread().getId());
    }

}
