package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.PageUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);
        if(shop==null)return Result.fail("店铺不存在");
        return Result.ok(shop);

    }
    //缓存击穿--互斥锁解法
    public Result queryWithMutex(Long id) throws InterruptedException {
        //缓存击穿解决方法：从缓存中查数据，查到返回，没有查到，进行互斥锁获取，判断是否获得锁，没有获得就休眠一段时间再进行尝试，获得锁就进行数据库查询，结果写入redis，释放锁，返回结果
        //从redis中查询信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //信息存在，返回
        if(StrUtil.isNotBlank(shopJson))return Result.ok(JSONUtil.toBean(shopJson,Shop.class));
        //解决缓存穿透，对于之前查过但是不存在的id，存的是""
        if(shopJson!=null)return Result.fail("数据错误");
        //不存在，获取锁，从数据库中查
        String lockkey="lcok:shop"+id;
        //获取锁成功
        if (tryLock(lockkey)) {
            Shop shop = getById(id);
            Thread.sleep(200);
            if(shop==null){
                //数据库中不存在，返回错误
                //解决缓存穿透，对于不存在的id，redis存的是“”
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return Result.fail("数据不存在");
            }
            //数据库中存在，写到redis中，返回
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
            unLock(lockkey);
            return Result.ok(shop);
        }
        //获取锁失败，先休眠一下，再尝试
        Thread.sleep(10);
        return queryById(id);

    }

    //缓存击穿--逻辑过期解法
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithLogicalExpire(Long id){
        //从redis中查缓存
        String key1=CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key1);
        //缓存未命中，直接返回空
        if(StrUtil.isBlank(shopJson))return null;
        //缓存命中，判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，返回数据
            return JSONUtil.toBean((JSONObject)redisData.getData(),Shop.class);
        }
        //过期，获取互斥锁，
        String key=LOCK_SHOP_KEY+id;
        if(tryLock(key)){
            //获取成功，开启独立线程，重建缓存，从数据库中查询数据，，写入缓存，释放互斥锁
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try{
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unLock(key);
                }
            });
        }
        //获取失败,返回缓存信息
        return JSONUtil.toBean((JSONObject)redisData.getData(),Shop.class);
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要根据坐标查询
        if(x==null||y==null){
            Page<Shop> page = query()
                    .eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
//        计算分页参数
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;

        //查询redis、按照距离排序、分页。结果：shopId、distance
//        stringRedisTemplate.opsForGeo()
        // 4.解析出id
        // 5.根据id查询Shop
        // 6.返回
        return null;
    }

    //缓存预热
    @Override
    public void saveShop2Redis(Long id, Long expireTime) {
        //根据ID查出数据
        Shop shop = getById(id);
        //封装成RedisData
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        //写入缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
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

    @Override
    @Transactional
    public Result update(Shop shop) {
        if(shop.getId()==null)return Result.fail("数据不能为空");
        //更新数据库
        boolean res = updateById(shop);
        if(res){
            //删除缓存
            stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
            return Result.ok();
        }
        return Result.fail("更新数据库，删除缓存失败");
    }
}