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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.PathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker idWorker;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private IVoucherOrderService proxy;

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                VoucherOrder voucherOrder = null;
                try {
                    voucherOrder = orderTasks.take();
                    System.out.println("进入run方法");
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //Long userId = UserHolder.getUser().getId();
        /**
         * 这里要注意，因为是异步线程调用的方法，所以他没有UserHolder对象
         * 根本拿不到 Long userId = UserHolder.getUser().getId();
         * 所以到这里会出错，
         */
        Long userId = voucherOrder.getUserId();
        //这里的锁多此一举，因为是单线程池，所以没有并发问题
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("你已经下单过一次");
            return;
        }
        try {
            proxy.createVoucherOrder2(voucherOrder);
        } finally {
            lock.unlock();
        }
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        //执行脚本
        Long res = redisTemplate.execute(SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), UserHolder.getUser().getId().toString());

        assert res != null;
        int r = res.intValue();
        if (r != 0) {
            System.out.println("失败");
            return Result.fail(r == 1 ? "订单库存不足" : "你已经下单过一次");
        }
        //阻塞队列异步保存到数据库
        long orderId = idWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //放入阻塞队列
        orderTasks.add(voucherOrder);
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder2(VoucherOrder voucherOrder) {

        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();

        int count = this.count(new QueryWrapper<VoucherOrder>()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId));
        if (count > 0) {
            log.error("用户已经购买了一次");
            return;
        }
        //乐观锁-CAS实现，判断stock没变也可以，但是不如 > 0
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).update();
        if (!success) {
            log.error("库存不足");
            return;
        }
        this.save(voucherOrder);
    }

    //    @Override  mysql版本
    public Result seckillVoucher_1(Long voucherId) {
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
        //redis分布式锁,之前是自己实现的锁，但是没有可重入人功能，这里该为redisson可重入锁
//        SimpleRedisLock lock = new SimpleRedisLock(redisTemplate, "order:" + userId);
//        boolean isLock = lock.tryLock();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();//三个参数，最大等待时间，超时释放时间，时间单位
        if (!isLock) {
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
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        this.save(voucherOrder);
        //返回订单Id
        return Result.ok(orderId);

    }
}
