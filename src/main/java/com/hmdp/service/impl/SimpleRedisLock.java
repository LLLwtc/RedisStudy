package com.hmdp.service.impl;

import com.hmdp.service.ILock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private StringRedisTemplate stringRedisTemplate;
    String name;
    private static final String KEYPREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString()+"-";

    //lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate,String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name=name;
    }

    @Override
    public Boolean tryLock(long timeoutSec) {
        //防止误删别人的锁
        //获取线程标识
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEYPREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        //获取线程标识
        //String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁的值
        //String id = stringRedisTemplate.opsForValue().get(KEYPREFIX + name);
        //确定是否是自己的锁
        //if(threadId==id){
        //    stringRedisTemplate.delete(KEYPREFIX+name);
        //}
        //使用lua脚本将拿锁比锁删锁封装为原子性操作
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEYPREFIX + name),
                ID_PREFIX+Thread.currentThread().getId());
    }
}
