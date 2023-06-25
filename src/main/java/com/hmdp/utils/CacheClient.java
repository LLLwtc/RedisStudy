package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓
    //存击穿问题
    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    //根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <R,ID> R queryWithPassThrough(
            String keyprefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit){
        //从redis中查缓存
        String key=keyprefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //存在，返回
        if(StrUtil.isNotBlank(json))return JSONUtil.toBean(json,type);
        //解决缓存穿透，对于之前查询过的id，缓存中存的是"",
        //判断命中的是否是空值
        if(json!=null)return null;
        //不存在，根据id查询数据库
        R res = dbFallback.apply(id);
        if(res==null){
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        this.set(key,res,time,unit);
        return res;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <R,ID> R queryWithLogicalExpire(
            String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit){
        //从redis中获取数据
        String key=keyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //为空直接返回空
        if(StrUtil.isBlank(json))return null;

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        R r1 = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //未过期，返回数据
        if(expireTime.isAfter(LocalDateTime.now())){
            return JSONUtil.toBean((JSONObject)redisData.getData(),type);
        }
        //过期，获取互斥锁
        String key1=LOCK_SHOP_KEY+id;
        if(tryLock(key1)){
            //获取成功，开启独立线程，重建缓存，从数据库中查询数据，，写入缓存，释放互斥锁
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    //查询数据库
                    R r = dbFallback.apply(id);
                    //重建缓存
                    this.setWithLogicalExpire(key,r1,time,unit);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unLock(key);
                }
            });
        }
        return r1;
    }

    //获取锁
    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
