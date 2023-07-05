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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

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
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    //lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks =new ArrayBlockingQueue<>(1024*1024);

    //代理对象
    IVoucherOrderService proxy;

    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR=Executors.newSingleThreadExecutor();

    //在类初始化之后执行，因为当这个类初始化好了之后，异步处理订单的操作随时都是有可能要执行的
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 用于线程池处理的任务
    // 当初始化完毕后，就会去从队列中去拿信息  handleVoucherOrder
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    //从队列中获取信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //生成订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                    e.printStackTrace();
                }
            }
        }
        //生成订单
        private void handleVoucherOrder(VoucherOrder voucherOrder) {

            Long userid = voucherOrder.getUserId();
            //获取锁（可重入），设置锁的名称
            RLock lock = redissonClient.getLock("lock:order"+userid);
            //尝试获取锁，参数：最大等待时间，过期时间，时间单位
            boolean success = lock.tryLock();
            if(success){
                try {
                    proxy.createVoucherOrder(voucherOrder);
                }finally {
                    lock.unlock();
                }
            }
            //获得锁失败
            log.error("不允许重复下单！");
            return;
        }

    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单逻辑
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();

        if(count>0){
            log.error("用户已经购买过一次");
            return ;
        }

        //是，扣减库存
        boolean success = seckillVoucherService.update().setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock",0).update();//eq("stock",voucher.getStock())乐观锁
        //扣减失败，返回异常
        if (!success) {
            log.error("库存不足");
            return ;
        }

        //扣减成功，创建订单。返回订单ID
        save(voucherOrder);//报错订单到数据库
    }

    //秒杀优化
    @Override
    public Result seckillVoucher(Long voucherId) throws InterruptedException {
        Long userId = UserHolder.getUser().getId();

        //执行lua脚本
        Long res = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());

        if(res.intValue()!=0){
            return Result.fail(res.intValue()==1?"库存不足":"不能重复下单");
        }

        //TODO 加入阻塞队列进行后续操作
        //1.将订单ID、代金券ID、用户ID封装在一起
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");

        voucherOrder.setId(orderId);//设置订单ID
        voucherOrder.setVoucherId(voucherId);//设置代金券ID
        voucherOrder.setUserId(UserHolder.getUser().getId());//设置用户id

        //2.放入阻塞队列
        orderTasks.add(voucherOrder);

        //3.异步处理阻塞队列中的订单时，需要用到代理对象，但异步处理是子进程，无法拿到父进程的代理对象，需要父进程拿到代理对象传给子进程
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) throws InterruptedException {
//        //查询秒杀优惠券信息
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if(voucher==null)return Result.fail("优惠券不存在");
//        //判断秒杀是否开始
//        //否。返回错误信息
//        LocalDateTime beginTime = voucher.getBeginTime();
//        LocalDateTime endTime = voucher.getEndTime();
//        if(beginTime.isAfter(LocalDateTime.now()))return Result.fail("活动未开始");
//        if(endTime.isBefore(LocalDateTime.now()))return Result.fail("活动已经结束");
//        //是，判断库存是否充足
//        Integer stock = voucher.getStock();
//        //否，返回异常
//        if(stock<1)return Result.fail("库存不足");
//
//        Long userid = UserHolder.getUser().getId();
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate, "order:"+id);
////        Boolean lock = simpleRedisLock.tryLock(1200);
//
//        //获取锁（可重入），设置锁的名称
//        RLock lock = redissonClient.getLock("lock:order"+userid);
//        //尝试获取锁，参数：最大等待时间，过期时间，时间单位
//        boolean success = lock.tryLock();
//        if(success){
//            try {
//                //获取代理对象（事务）
//                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//                return proxy.createVoucherOrder(voucherId);
//            }finally {
////                simpleRedisLock.unlock();
//                lock.unlock();
//            }
//        }
//        //获得锁失败
//        return Result.fail("不允许重复下单");
//    }

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
