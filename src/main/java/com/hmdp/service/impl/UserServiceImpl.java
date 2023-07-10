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
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //获取用户
        Long userId = UserHolder.getUser().getId();
//      获取当前日期
        LocalDateTime now = LocalDateTime.now();
//      设置key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+userId.toString()+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
//        存入redis
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        //获取用户
        Long userId = UserHolder.getUser().getId();
//      获取当前日期
        LocalDateTime now = LocalDateTime.now();
//      设置key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+userId.toString()+keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate
                .opsForValue()
                .bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));

        if (result==null||result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num==null||num==0) {
            return Result.ok(0);
        }
        //从最后一次签到开始向前统计，直到遇到第一次未签到为止，计算总的签到次数，就是连续签到天数
        //循环遍历
        int count=0;
        while (true){
            if ((num&1)==0) {
                break;
            }
            else {
                count++;
            }
            num>>>=1;//无符号位右移
        }
        return Result.ok(count);
    }

    public User createUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
}
