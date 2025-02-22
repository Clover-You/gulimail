package top.ctong.gulimall.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import top.ctong.gulimall.common.to.mq.OrderTo;
import top.ctong.gulimall.common.to.mq.SeckillOrderTo;
import top.ctong.gulimall.common.utils.PageUtils;
import top.ctong.gulimall.common.utils.Query;
import top.ctong.gulimall.common.utils.R;
import top.ctong.gulimall.common.vo.MemberRespVo;
import top.ctong.gulimall.order.components.interceptor.LoginInterceptor;
import top.ctong.gulimall.order.constant.OrderConstant;
import top.ctong.gulimall.order.dao.OrderDao;
import top.ctong.gulimall.order.entity.OrderEntity;
import top.ctong.gulimall.order.entity.OrderItemEntity;
import top.ctong.gulimall.order.entity.PaymentInfoEntity;
import top.ctong.gulimall.order.enume.OrderStatusEnum;
import top.ctong.gulimall.order.feign.CartFeignService;
import top.ctong.gulimall.order.feign.MemberFeignService;
import top.ctong.gulimall.order.feign.ProductFeignService;
import top.ctong.gulimall.order.feign.WmsFeignService;
import top.ctong.gulimall.order.service.OrderItemService;
import top.ctong.gulimall.order.service.OrderService;
import top.ctong.gulimall.order.service.PaymentInfoService;
import top.ctong.gulimall.order.to.*;
import top.ctong.gulimall.order.vo.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * █████▒█      ██  ▄████▄   ██ ▄█▀     ██████╗ ██╗   ██╗ ██████╗
 * ▓██   ▒ ██  ▓██▒▒██▀ ▀█   ██▄█▒      ██╔══██╗██║   ██║██╔════╝
 * ▒████ ░▓██  ▒██░▒▓█    ▄ ▓███▄░      ██████╔╝██║   ██║██║  ███╗
 * ░▓█▒  ░▓▓█  ░██░▒▓▓▄ ▄██▒▓██ █▄      ██╔══██╗██║   ██║██║   ██║
 * ░▒█░   ▒▒█████▓ ▒ ▓███▀ ░▒██▒ █▄     ██████╔╝╚██████╔╝╚██████╔╝
 * ▒ ░   ░▒▓▒ ▒ ▒ ░ ░▒ ▒  ░▒ ▒▒ ▓▒     ╚═════╝  ╚═════╝  ╚═════╝
 * ░     ░░▒░ ░ ░   ░  ▒   ░ ░▒ ▒░
 * ░ ░    ░░░ ░ ░ ░        ░ ░░ ░
 * ░     ░ ░      ░  ░
 * Copyright 2021 Clover You.
 * <p>
 * 订单
 * </p>
 * @author Clover You
 * @email 2621869236@qq.com
 * @create 2021-11-16 16:11:06
 */
@Slf4j
@Service("orderService")
public class OrderServiceImpl extends ServiceImpl<OrderDao, OrderEntity> implements OrderService {

    @Autowired
    private MemberFeignService memberFeignService;

    @Autowired
    private CartFeignService cartFeignService;

    @Autowired
    private ThreadPoolExecutor executor;

    @Autowired
    private ProductFeignService productFeignService;

    @Autowired
    private WmsFeignService wmsFeignService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private OrderItemService orderItemService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private PaymentInfoService paymentInfoService;

    /**
     * 保存订单提交时的数据
     */
    private final ThreadLocal<OrderSubmitVo> ORDER_SUBMIT_VO_THREAD_LOCAL = new ThreadLocal<>();

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<OrderEntity> page = this.page(
            new Query<OrderEntity>().getPage(params),
            new QueryWrapper<OrderEntity>()
        );

        return new PageUtils(page);
    }

    /**
     * 订单确认信息
     * @return OrderConfirmVo
     * @author Clover You
     * @date 2022/2/25 2:03 下午
     */
    @Override
    public OrderConfirmVo confirmOrder() {
        MemberRespVo mrv = LoginInterceptor.THREAD_LOCAL.get();
        OrderConfirmVo vo = new OrderConfirmVo();
        // 获取当前线程ThreadLocal
        RequestAttributes myReqContext = RequestContextHolder.currentRequestAttributes();

        // 查询会员所有收货地址
        CompletableFuture<Void> memberFuture = CompletableFuture.runAsync(() -> {
            RequestContextHolder.setRequestAttributes(myReqContext);
            R memberReceiveAddress = memberFeignService.getMemberReceiveAddress(mrv.getId());
            if (memberReceiveAddress.getCode() == 0) {
                List<MemberAddressTo> addresses = memberReceiveAddress.getData(new TypeReference<List<MemberAddressTo>>() {
                });
                vo.setAddress(addresses);
                return;
            }
            vo.setAddress(new ArrayList<>(0));

        }, executor);

        // 获取购物项
        CompletableFuture<Void> cartItemFuture = CompletableFuture.runAsync(() -> {
            RequestContextHolder.setRequestAttributes(myReqContext);
            R cartItem = cartFeignService.getCurrentUserCartItem();
            if (cartItem.getCode() != 0) {
                vo.setItems(new ArrayList<>(0));
                return;
            }
            List<OrderItemVo> itemData = cartItem.getData(new TypeReference<List<OrderItemVo>>() {
            });
            vo.setItems(itemData);
        }, executor);

        // 库存设置
        cartItemFuture.thenRunAsync(() -> {
            List<Long> skuIds = vo.getItems().stream().map(OrderItemVo::getSkuId).collect(Collectors.toList());
            R stock = wmsFeignService.getSkuHasStock(skuIds);
            if (stock.getCode() != 0) {
                return;
            }
            List<SkuHasStockTo> list = stock.getData(new TypeReference<List<SkuHasStockTo>>() {
            });
            Map<Long, Boolean> stocks = list.stream().collect(Collectors.toMap(
                SkuHasStockTo::getSkuId,
                SkuHasStockTo::getHasStock
            ));

            vo.setStocks(stocks);
        }, executor);

        // 积分设置
        vo.setIntegration(mrv.getIntegration());

        // 设置防重令牌
        String uuidToken = UUID.randomUUID().toString().replace("-", "");
        vo.setOrderToken(uuidToken);
        String tokenKey = OrderConstant.USER_ORDER_TOKEN_PREFIX + mrv.getId().toString();
        stringRedisTemplate.opsForValue().set(tokenKey, uuidToken, 30, TimeUnit.MINUTES);

        CompletableFuture.allOf(memberFuture, cartItemFuture).join();

        return vo;
    }

    /**
     * 创建订单（下单）
     * @param vo 订单信息
     * @return SubmitOrderResponseVo
     * @author Clover You
     * @date 2022/2/27 9:17 上午
     */
//    @GlobalTransactional
    @Transactional
    @Override
    public SubmitOrderResponseVo submitOrder(OrderSubmitVo vo) throws Exception {
        SubmitOrderResponseVo resp = new SubmitOrderResponseVo();
        resp.setCode(0);
        MemberRespVo mrv = LoginInterceptor.THREAD_LOCAL.get();

        //#region 创建订单、验证 token、验证价格、锁库存
        // 验证 token（验证和删除 token 必须保证原子性）
        String tokenKey = OrderConstant.USER_ORDER_TOKEN_PREFIX + mrv.getId().toString();
        String token = vo.getOrderToken();

        // 获取一个具有原子性的lua脚本
        String verificationLuaScript = getVerificationLuaScriptForOrderToken();
        DefaultRedisScript<Boolean> redisScript = new DefaultRedisScript<>(verificationLuaScript, Boolean.class);
        Boolean verificationResult = stringRedisTemplate.<Boolean>execute(
            redisScript,
            Collections.singletonList(tokenKey),
            token
        );

        // check verification result for token...
        if (Boolean.FALSE.equals(verificationResult)) {
            log.debug("create order service ===>> token verification fail...");
            resp.setCode(1);
            return resp;
        }

        ORDER_SUBMIT_VO_THREAD_LOCAL.set(vo);

        OrderCreateTo order = createOrder();
        resp.setOrder(order);

        // 验证订单价格
        OrderEntity orderEntity = order.getOrder();
        BigDecimal payAmount = orderEntity.getPayAmount();
        // 如果差价在小于0.01
        if (Math.abs(vo.getPayPrice().subtract(payAmount).doubleValue()) >= 0.01) {
            resp.setCode(2);
            return resp;
        }

        // 保存订单
        saveOrder(order);
        //#region 锁定库存
        WareSkuLockTo wareSkuLockTo = new WareSkuLockTo();
        wareSkuLockTo.setOrderSn(order.getOrder().getOrderSn());
        // OrderItemEntity ===>> OrderItemVo
        List<OrderItemVo> wareLockOrderItemVo = order.getOrderItems().stream().map((item) -> {
            OrderItemVo orderItemVo = new OrderItemVo();
            orderItemVo.setSkuId(item.getSkuId());
            orderItemVo.setCount(item.getSkuQuantity());
            orderItemVo.setTitle(item.getSkuName());
            return orderItemVo;
        }).collect(Collectors.toList());
        wareSkuLockTo.setLocks(wareLockOrderItemVo);

        R lR = wmsFeignService.orderLockStock(wareSkuLockTo);
        if (lR.getCode() != 0) {
            String msg = lR.getMsg();
            log.error("create order service ===>>{}", msg);
            resp.setCode(3);
            return resp;
        }
        //#endregion

        // 订单创建成功，发送消息给MQ
        rabbitTemplate.convertAndSend("order-event-exchange", "order.create.order", orderEntity);
        //#endregion
        return resp;
    }

    /**
     * 保存订单
     * @param order 订单信息
     * @author Clover You
     * @date 2022/2/27 8:04 下午
     */
    private void saveOrder(OrderCreateTo order) {
        List<OrderItemEntity> orderItems = order.getOrderItems();
        OrderEntity orderInfo = order.getOrder();
        orderInfo.setModifyTime(new Date());
        this.save(orderInfo);
        Long orderId = orderInfo.getId();
        orderItems.forEach((item) -> item.setOrderId(orderId));
        orderItemService.saveBatch(orderItems);
    }

    /**
     * 获取一个redis可以验证指定 token 带有原子性操作的lua脚本
     * @return String
     * @author Clover You
     * @date 2022/2/27 9:35 上午
     */
    private String getVerificationLuaScriptForOrderToken() {
        return "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    }

    /**
     * 创建订单
     * @return CreateOrderTo
     * @author Clover You
     * @date 2022/2/27 10:02 上午
     */
    private OrderCreateTo createOrder() {

        //创建订单
        OrderCreateTo orderInfo = createOrderInfo();
        OrderEntity order = orderInfo.getOrder();
        // 获取当前用户购物车数据
        List<OrderItemEntity> orderItemEntities = buildOrderItems(order.getOrderSn());
        orderInfo.setOrderItems(orderItemEntities);

        // 计算订单价格
        computeOrderPrice(orderInfo);

        return orderInfo;
    }

    /**
     * 创建订单基础信息
     * @return OrderCreateTo
     * @author Clover You
     * @date 2022/2/27 4:45 下午
     */
    private OrderCreateTo createOrderInfo() {
        OrderCreateTo order = new OrderCreateTo();
        OrderEntity orderEntity = new OrderEntity();
        String orderNo = IdWorker.getTimeId();
        orderEntity.setOrderSn(orderNo);
        orderEntity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());
        orderEntity.setAutoConfirmDay(7);
        orderEntity.setDeleteStatus(0);

        OrderSubmitVo osVo = ORDER_SUBMIT_VO_THREAD_LOCAL.get();
        MemberRespVo mrv = LoginInterceptor.THREAD_LOCAL.get();
        // 设置会员id
        orderEntity.setMemberId(mrv.getId());


        // 设置地址
        Long addrId = osVo.getAddrId();
        R fareR = wmsFeignService.getFare(addrId);

        if (fareR.getCode() != 0) {
            // 错误
            log.error("fare query fail...");
        }
        FareVo fare = fareR.getData(
            "data",
            new TypeReference<FareVo>() {
            }
        );
        order.setFare(fare.getFare());
        MemberAddressTo address = fare.getAddress();
        // 运费
        orderEntity.setFreightAmount(fare.getFare());
        // 省
        orderEntity.setReceiverProvince(address.getProvince());
        // 市
        orderEntity.setReceiverCity(address.getCity());
        // 区
        orderEntity.setReceiverRegion(address.getRegion());
        // 详细地址
        orderEntity.setReceiverDetailAddress(address.getDetailAddress());
        // 收货人邮编
        orderEntity.setReceiverPostCode(address.getPostCode());
        // 收货人手机号
        orderEntity.setReceiverPhone(address.getPhone());
        // 收货人姓名
        orderEntity.setReceiverName(address.getName());

        order.setOrder(orderEntity);
        return order;
    }

    /**
     * 计算订单价格
     * @param order 订单信息
     * @author Clover You
     * @date 2022/2/27 4:42 下午
     */
    private void computeOrderPrice(OrderCreateTo order) {
        OrderEntity orderEntity = order.getOrder();
        List<OrderItemEntity> orderItemEntities = order.getOrderItems();
        // 订单总额、应付总额、运费金额、促销优化金额（促销价、满减、阶梯价）、积分抵扣金额、优惠券抵扣金额
        // 计算订单总价 (单价 * 数量 + n)
        BigDecimal orderTotalPrice = new BigDecimal("0.00");
        // 促销优惠金额
        BigDecimal promotionAmount = new BigDecimal("0.00");
        // 积分抵扣金额
        BigDecimal integrationAmount = new BigDecimal("0.00");
        // 优惠卷金额
        BigDecimal couponAmount = new BigDecimal("0.00");
        // 订单能获取的积分
        Integer giftIntegration = 0;
        // 订单能获取的成长值
        Integer giftGrowth = 0;
        for (OrderItemEntity itemEntity : orderItemEntities) {
            orderTotalPrice = orderTotalPrice.add(itemEntity.getRealAmount());
            promotionAmount = promotionAmount.add(itemEntity.getPromotionAmount());
            integrationAmount = integrationAmount.add(itemEntity.getIntegrationAmount());
            couponAmount = couponAmount.add(itemEntity.getCouponAmount());
            giftIntegration += itemEntity.getGiftIntegration();
            giftGrowth += itemEntity.getGiftGrowth();

        }
        orderEntity.setTotalAmount(orderTotalPrice);
        orderEntity.setPromotionAmount(promotionAmount);
        orderEntity.setIntegrationAmount(integrationAmount);
        orderEntity.setCouponAmount(couponAmount);

        orderEntity.setIntegration(giftIntegration);
        orderEntity.setGrowth(giftGrowth);

        BigDecimal farePrice = new BigDecimal("0.00");
        if (order.getFare() != null) {
            farePrice = order.getFare();
        }
        orderEntity.setFreightAmount(farePrice);

        // 应付金额
        orderEntity.setPayAmount(orderTotalPrice.add(farePrice));
    }

    /**
     * 构建订单项
     * @param orderNo 订单号
     * @return OrderItemEntity
     * @author Clover You
     * @date 2022/2/27 11:09 上午
     */
    private List<OrderItemEntity> buildOrderItems(String orderNo) {
        R userCartInfoR = cartFeignService.getCurrentUserCartItem();
        if (!userCartInfoR.getCode().equals(0)) {
            log.error("create order service ===>> user cart query fail...");
        }
        List<OrderItemVo> dataList = userCartInfoR.getData(new TypeReference<List<OrderItemVo>>() {
        });
        if (dataList == null || dataList.isEmpty()) {
            log.error("create order service ===>> cart is empty...");
            return new ArrayList<>(0);
        }
        return dataList.stream().map(data -> {
            // 存在逻辑问题，异步等于没有
            OrderItemEntity itemEntity = buildOrderItem(data);
            itemEntity.setOrderSn(orderNo);
            return itemEntity;
        }).collect(Collectors.toList());
    }

    /**
     * 构建指定订单项
     * @param data 订单项数据
     * @return OrderItemEntity
     * @author Clover You
     * @date 2022/2/27 2:48 下午
     */
    private OrderItemEntity buildOrderItem(OrderItemVo data) {
        // 构建订单项
        OrderItemEntity entity = new OrderItemEntity();
        entity.setSkuId(data.getSkuId());

        //  商品 spu 信息
        R spuInfoR = productFeignService.getSpuInfoBySkuId(data.getSkuId());
        SpuInfoTo spuInfo = spuInfoR.getData(new TypeReference<SpuInfoTo>() {
        });

        // 获取品牌名称
        CompletableFuture<Void> brandInfoFuture = CompletableFuture.runAsync(() -> {
            R brandInfoR = productFeignService.getBrandInfo(spuInfo.getBrandId());
            BrandInfoTo brandInfo = brandInfoR.getData("brand", new TypeReference<BrandInfoTo>() {
            });
            entity.setSpuBrand(brandInfo.getName());
        }, executor);
        // 如果有异常，那么将品牌id作为品牌名称
        brandInfoFuture.whenComplete((v, e) -> {
            entity.setSpuBrand(spuInfo.getBrandId().toString());
        });

        entity.setSpuId(spuInfo.getId());
        entity.setSpuName(spuInfo.getSpuName());

        // 商品 SKU 信息
        entity.setSkuId(data.getSkuId());
        entity.setSkuName(data.getTitle());
        entity.setSkuAttrsVals(
            // 将一个集合转为字符串通过指定分隔符分割
            StringUtils.collectionToDelimitedString(data.getSkuAttr(), ";")
        );
        entity.setSkuPic(data.getImage());
        entity.setSkuPrice(data.getPrice());
        entity.setSkuQuantity(data.getCount());

        //  积分信息
        entity.setGiftGrowth(data.getPrice().intValue());
        entity.setGiftIntegration(data.getPrice().intValue());

        // 订单项价格
        entity.setPromotionAmount(new BigDecimal("0"));
        entity.setCouponAmount(new BigDecimal("0"));
        entity.setIntegrationAmount(new BigDecimal("0"));
        // 订单项总价
        BigDecimal skuTotalPrice = entity.getSkuPrice().multiply(new BigDecimal(entity.getSkuQuantity()));
        // 但前订单项实际价格
        BigDecimal realPrice = skuTotalPrice.subtract(entity.getPromotionAmount())
            .subtract(entity.getCouponAmount())
            .subtract(entity.getIntegrationAmount());
        entity.setRealAmount(realPrice);

        // 等待品牌查询完成
        brandInfoFuture.join();
        return entity;
    }

    /**
     * 根据订单号获取订单状态
     * @param orderSn 订单
     * @return OrderEntity
     * @author Clover You
     * @date 2022/3/7 4:45 下午
     */
    @Override
    public OrderEntity getOrderStatus(String orderSn) {
        return baseMapper.selectOne(new QueryWrapper<OrderEntity>().eq("order_sn", orderSn));
    }

    /**
     * 关闭订单
     * @param orderEntity 订单信息
     * @author Clover You
     * @date 2022/3/8 9:37 上午
     */
    @Override
    public void closeOrder(OrderEntity orderEntity) {
        OrderEntity orderRealInfo = getById(orderEntity.getId());
        // 如果执行到这该订单还是未付款，那么肯定是超时单
        if (!orderRealInfo.getStatus().equals(OrderStatusEnum.CREATE_NEW.getCode())) {
            return;
        }

        OrderEntity newInfo = new OrderEntity();
        // 设置订单为取消
        newInfo.setStatus(OrderStatusEnum.CANCELED.getCode());
        newInfo.setId(orderRealInfo.getId());

        updateById(newInfo);

        OrderTo orderTo = new OrderTo();
        BeanUtils.copyProperties(orderRealInfo, orderTo);

        // 给库存服务发送消息
        rabbitTemplate.convertAndSend(
            "order-event-exchange",
            "order.release.other",
            orderTo
        );
    }

    /**
     * 获取订单数据并返回支付vo
     * @param orderSn 订单
     * @return PayVo
     * @author Clover You
     * @date 2022/3/10 9:29 上午
     */
    @Override
    public PayVo getOrderPayInfo(String orderSn) {
        PayVo payVo = new PayVo();
        OrderEntity orderEntity = this.getOne(
            new QueryWrapper<OrderEntity>().eq("order_sn", orderSn).eq("status", 0)
        );

        String payAmount = orderEntity.getPayAmount().setScale(2, BigDecimal.ROUND_UP).toString();
        payVo.setTotalAmount(payAmount);
        payVo.setOutTradeNo(orderEntity.getOrderSn());

        List<OrderItemEntity> orderItemList = orderItemService.list(
            new QueryWrapper<OrderItemEntity>()
                .eq("order_id", orderEntity.getId())
        );
        List<String> spuNames = orderItemList.stream().map(OrderItemEntity::getSpuName).collect(Collectors.toList());
        payVo.setSubject(orderItemList.get(0).getSkuName() + "...");
        payVo.setBody(spuNames.toString());

        return payVo;
    }

    /**
     * 分页获取用户订单
     * @param params 请求参数
     * @return PageUtils
     * @author Clover You
     * @email cloveryou02@163.com
     * @date 2022/3/11 1:52 下午
     */
    @Override
    public PageUtils queryPageWithItem(Map<String, Object> params) {

        MemberRespVo mrv = LoginInterceptor.THREAD_LOCAL.get();
        IPage<OrderEntity> page = this.page(
            new Query<OrderEntity>().getPage(params),
            new QueryWrapper<OrderEntity>().eq("member_id", mrv.getId()).orderByDesc("id")
        );
        List<OrderEntity> list = page.getRecords().stream().map(order -> {
            List<OrderItemEntity> item = orderItemService.list(
                new QueryWrapper<OrderItemEntity>().eq("order_id", order.getId())
            );
            order.setItems(item);
            return order;
        }).collect(Collectors.toList());

        page.setRecords(list);

        return new PageUtils(page);
    }

    /**
     * 处理支付宝支付结果
     * @param params 支付宝异步通知参数
     * @author Clover You
     * @email cloveryou02@163.com
     * @date 2022/3/13 2:49 下午
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void handleAlipayResult(PayAsyncVo params) throws Exception {
        // 保存交易流水信息
        PaymentInfoEntity paymentInfoEntity = new PaymentInfoEntity();
        paymentInfoEntity.setOrderSn(params.getOut_trade_no());
        paymentInfoEntity.setAlipayTradeNo(params.getTrade_no());
        paymentInfoEntity.setPaymentStatus(params.getTrade_status());
        paymentInfoEntity.setCallbackTime(params.getNotify_time());
        paymentInfoEntity.setCreateTime(new Date());
        paymentInfoEntity.setTotalAmount(new BigDecimal(params.getTotal_amount()));
        paymentInfoService.save(paymentInfoEntity);

        String tradeStatus = params.getTrade_status();
        if (!"TRADE_SUCCESS".equals(tradeStatus) && !"TRADE_FINISHED".equals(tradeStatus)) {
            throw new Exception("支付失败");
        }

        // 订单号
        String orderNo = params.getOut_trade_no();
        OrderEntity orderEntity = getOrderStatus(orderNo);
        if (orderEntity == null) {
            throw new Exception("订单不存在");
        }

        OrderEntity entity = new OrderEntity();
        entity.setOrderSn(orderNo);
        entity.setId(orderEntity.getId());
        entity.setStatus(OrderStatusEnum.PAYED.getCode());
        entity.setPayType(1);
        this.updateById(entity);
    }

    /**
     * 创建秒杀单订单
     * @param seckillOrderTo 秒杀单信息
     * @author Clover You
     * @email cloveryou02@163.com
     * @date 2022/3/16 6:13 下午
     */
    @Transactional
    @Override
    public void createSeckillOrder(SeckillOrderTo seckillOrderTo) {
        // TODO 创建订单号
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderSn(seckillOrderTo.getOrderSn());
        orderEntity.setMemberId(seckillOrderTo.getMemberId());
        orderEntity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());
        BigDecimal payAmount = seckillOrderTo.getSeckillPrice().multiply(
            new BigDecimal(seckillOrderTo.getNum())
        );
        orderEntity.setPayAmount(payAmount);
        orderEntity.setCreateTime(new Date());
        this.save(orderEntity);

        // 保存订单项
        OrderItemEntity orderItemEntity = new OrderItemEntity();
        orderItemEntity.setOrderSn(seckillOrderTo.getOrderSn());
        orderItemEntity.setOrderId(orderEntity.getId());
        orderItemEntity.setRealAmount(payAmount);
        orderItemEntity.setSkuQuantity(seckillOrderTo.getNum());

//        R skuR = productFeignService.getSpuInfoBySkuId(seckillOrderTo.getSkuId());
//        if (skuR.getCode() != 0) {
//            return;
//        }
//        skuR.getData(new TypeReference<SpuInfoTo>() {
//        })

        orderItemService.save(orderItemEntity);
    }
}