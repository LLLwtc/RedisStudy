package com.hmdp;

import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Test
    public void test1(){
        System.out.println(shopService);
        shopService.saveShop2Redis(1L,10L);
    }
}
