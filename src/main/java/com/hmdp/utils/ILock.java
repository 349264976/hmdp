package com.hmdp.utils;

public interface ILock {

    /**
     * Lock the尝试获取锁
     * @param timeoutSec 锁持有得超市时间 过期后自动释放
     * @return true代表获取锁成功； false代表获取锁失
     */
    boolean trylock(long timeoutSec);

    /**
     * Unlock the
     */
    void unlock();
}
