package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate redisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate=redisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //从redis中获取用户信息
        String token = request.getHeader("authorization");
        //token不存在，放行
        if(StrUtil.isBlank(token)){
            return true;
        }
        //基于token获取redis中的用户
        Map<Object, Object> userMap = redisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        //用户信息是否为空
        if(userMap.isEmpty()){
            return true;
        }
        //将查询到的hash数据转为userDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        //将用户信息放到ThreadLocal中
        UserHolder.saveUser(userDTO);
        //刷新token的有效期
        redisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL, TimeUnit.MINUTES);
        //放行
        return true;
    }
}
