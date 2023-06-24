package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // TODO 发送短信验证码并保存验证码
        //1. 效验手机号是否合法,合法返回FALSE
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        System.out.println(phoneInvalid);
        //1.1不合法返回错误
        if(phoneInvalid)return Result.fail("手机号不合法");
        //2. 合法生成验证码
        String code = RandomUtil.randomNumbers(6);
        //3. 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4. 发送验证码
        log.debug("发送短信验证码成功，短信验证码为:{}"+code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // TODO 实现登录功能
        //判断手机号是否合法
        String phone = loginForm.getPhone();
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        if(phoneInvalid)return Result.fail("手机号不合法");

        //基于token，从redis中获取验证码，判断验证码是否正确
        String TrueCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if(!TrueCode.equals(code)|| StrUtil.isBlank(code))return Result.fail("验证码错误");

        //根据手机号查询用户，用户是否存在
        User user = query().eq("phone", loginForm.getPhone()).one();
        //不存在则创建用户
        if(user==null){
            user = createUserWithPhone(phone);
        }

        //生成token
        String token = UUID.randomUUID().toString();
        //将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //将用户信息存储在redis中
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,userMap);
        //设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    public User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
}
