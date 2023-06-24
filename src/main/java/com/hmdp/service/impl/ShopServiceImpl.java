package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

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
    @Override
//    public Result queryById(Long id) {
//        //从redis中查询信息
//        String key = CACHE_SHOP_KEY + id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //信息存在，返回
//        if(StrUtil.isNotBlank(shopJson))return Result.ok(JSONUtil.toBean(shopJson,Shop.class));
//        //解决缓存穿透，对于不存在的id，""
//        if(shopJson!=null)return Result.fail("数据错误");
//        //不存在，从数据库中查
//        Shop shop = getById(id);
//        if(shop==null){
//            //数据库中不存在，返回错误
//            //解决缓存穿透，对于不存在的id，redis存的是null
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return Result.fail("数据不存在");
//        }
//        //数据库中存在，写到redis中，返回
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        return Result.ok(shop);
//    }

    public Result queryById(Long id) throws InterruptedException {
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
        if (tryLock(key)) {
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