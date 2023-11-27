package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker idWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //判断秒杀是否开始/结束 直接返回未开始/结束
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        //如果开始，判断库存
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        //redis分布式锁
        SimpleRedisLock lock = new SimpleRedisLock(redisTemplate, "order" + userId);
        if (!lock.tryLock(1200L)) {
            return Result.fail("你已经下单过一次");
        }
        //事务异常，直接释放锁
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
        //toString().intern()返回的是常量池的对象，值相同就相同，所以可以锁住同一userid
        //但是在分布式系统下，不同服务器有不同的jvm内存，即不同锁监视器，所以该方式不能实现分布式锁
//        synchronized (userId.toString().intern()) {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            //如果用this直接调用createVoucherOrder的话，没有事务特征，
//            //因为aop是spring用代理实现的，
//            //所以要拿到它的代理对象->来调用该函数，才有事务特征
//            //还需要引入依赖，aspectjweaver，
//            //以及暴露@EnableAspectJAutoProxy(exposeProxy = true)代理对象
//            return proxy.createVoucherOrder(voucherId);
//        }
        //如果该方法加事务，会超卖的原因:
        //只有该方法执行到最下面的大括号才会结束，但此时锁已经释放，
        //很可能有同一userId再次获得锁，而事务还没提交，数据库中的count还是0，所以发生超卖
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        int count = this.count(new QueryWrapper<VoucherOrder>()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId));

        if (count > 0) {
            return Result.fail("您已经下过单了");
        }
        //乐观锁-CAS实现，判断stock没变也可以，但是不如 > 0
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0).update();

        if (!success) {
            return Result.fail("库存不足132");
        }
        //库存充足，创建订单,
        long orderId = idWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(idWorker.nextId("order"));
        voucherOrder.setUserId(UserHolder.getUser().getId());
        this.save(voucherOrder);
        //返回订单Id
        return Result.ok(orderId);

    }
}
