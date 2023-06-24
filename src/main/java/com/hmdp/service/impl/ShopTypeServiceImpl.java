package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result Query() {
        //从redis中查询
        String key = CACHE_SHOPTYPE_KEY;
        String shopType = stringRedisTemplate.opsForValue().get(key);
        //存在则返回，不存在则从数据库中查
        if(StrUtil.isNotBlank(shopType))return Result.ok(JSONUtil.toList(shopType,ShopType.class));
        //数据库中存在则写入redis，返回
        List<ShopType> shortShopType = query().orderByAsc("sort").list();
        //不存在则报错
        if(shortShopType.isEmpty())return Result.fail("数据不存在");
        //存在，写入redis，返回
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shortShopType),CACHE_SHOPTYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(shortShopType);
    }
}
//先查，再排序，再转化为list
