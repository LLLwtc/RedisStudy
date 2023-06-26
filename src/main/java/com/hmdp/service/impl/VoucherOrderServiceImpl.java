package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询秒杀优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(voucher==null)return Result.fail("优惠券不存在");
        //判断秒杀是否开始
        //否。返回错误信息
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        if(beginTime.isAfter(LocalDateTime.now()))return Result.fail("活动未开始");
        if(endTime.isBefore(LocalDateTime.now()))return Result.fail("活动已经结束");
        //是，判断库存是否充足
        Integer stock = voucher.getStock();
        //否，返回异常
        if(stock<1)return Result.fail("库存不足");
        //锁
        Long id = UserHolder.getUser().getId();
        synchronized (id.toString().intern()) {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单逻辑
        int count = query().eq("user_id", UserHolder.getUser().getId())
                .eq("voucher_id", voucherId).count();
        if(count>0)return Result.fail("用户已经购买过一次");
        //是，扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock",0).update();//eq("stock",voucher.getStock())乐观锁
        //扣减失败，返回异常
        if (!success) {
            return Result.fail("库存不足");
        }

        //扣减成功，创建订单。返回订单ID
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));//设置订单ID
        voucherOrder.setVoucherId(voucherId);//设置代金券ID
        voucherOrder.setUserId(UserHolder.getUser().getId());//设置用户id

        save(voucherOrder);//报错订单到数据库
        return Result.ok(voucherOrder);
    }
}
