package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void test1(){
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    public void loadShopData(){
        //查询店铺信息
        List<Shop> shopList = shopService.list();
        //按typedId分组
        Map<Long, List<Shop>> shopMap = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
//        分批写入redis

        for (Map.Entry<Long, List<Shop>> entry : shopMap.entrySet()) {
            Long typedId = entry.getKey();
            String key=SHOP_GEO_KEY+typedId;

            List<Shop> shopList1 = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shopList1.size());
            for (Shop shop : shopList1) {
//                stringRedisTemplate.opsForGeo().add(typedId.toString(),new Point(shop.getX(),shop.getY()),shop.getId().toString());
                locations.add(
                        new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
                stringRedisTemplate.opsForGeo().add(key.toString(),locations);
            }
        }
    }
}
