package com.hmdp.utils;

/*
   @Date 2023/11/26-21:46
   @author fff
*/
public interface ILock {

    /**
     * 尝试获得锁
     * @param timeoutSec 持有锁秒数，过期自动释放，防止redis宕机
     * @return 返回获得锁是否成功
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
