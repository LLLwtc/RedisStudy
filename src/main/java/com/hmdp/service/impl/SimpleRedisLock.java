package com.hmdp.service.impl;

import com.hmdp.service.ILock;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private StringRedisTemplate stringRedisTemplate;
    String name;
    private static final String KEYPREFIX="lock:";

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate,String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name=name;
    }

    @Override
    public Boolean tryLock(long timeoutSec) {
        long threadId = Thread.currentThread().getId();

        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEYPREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(KEYPREFIX+name);
    }
}
