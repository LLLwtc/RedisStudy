package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

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
    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if(voucher==null)return Result.fail("优惠券不存在");
        //判断秒杀是否开始
        LocalDateTime beginTime = voucher.getBeginTime();
        voucher.getEndTime()
        if(beginTime.isAfter(LocalDateTime.now())return Result.fail("活动未开始");
        if()
        //否。返回异常
        //是，判断库存是否充足
        //否，返回异常
        //是，扣减库存
        //扣减失败，返回异常
        //扣减成功，创建订单。返回订单ID
    }
}
