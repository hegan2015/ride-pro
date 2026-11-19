package com.qoder.ride.order.service;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;
import com.qoder.ride.common.util.GeoUtil;
import com.qoder.ride.common.util.JsonUtil;
import com.qoder.ride.facade.common.PageDTO;
import com.qoder.ride.facade.dispatch.DispatchFacade;
import com.qoder.ride.facade.dispatch.DispatchRequest;
import com.qoder.ride.facade.dispatch.DispatchResultDTO;
import com.qoder.ride.facade.driver.DriverDTO;
import com.qoder.ride.facade.driver.DriverFacade;
import com.qoder.ride.facade.location.LocationFacade;
import com.qoder.ride.facade.location.TrackPointDTO;
import com.qoder.ride.facade.order.CallRequest;
import com.qoder.ride.facade.order.OrderDTO;
import com.qoder.ride.facade.order.OrderQuery;
import com.qoder.ride.facade.order.OrderStatsDTO;
import com.qoder.ride.facade.order.RatingDTO;
import com.qoder.ride.facade.order.RatingRequest;
import com.qoder.ride.facade.passenger.PassengerDTO;
import com.qoder.ride.facade.passenger.PassengerFacade;
import com.qoder.ride.facade.payment.PayRequest;
import com.qoder.ride.facade.payment.PayResultDTO;
import com.qoder.ride.facade.payment.PaymentFacade;
import com.qoder.ride.facade.payment.RefundRequest;
import com.qoder.ride.facade.payment.RefundSummaryDTO;
import com.qoder.ride.facade.payment.TransferRequest;
import com.qoder.ride.facade.pricing.FareDTO;
import com.qoder.ride.facade.pricing.FareRequest;
import com.qoder.ride.facade.pricing.PricingFacade;
import com.qoder.ride.facade.push.PushFacade;
import com.qoder.ride.order.config.OrderProperties;
import com.qoder.ride.order.dataobject.IdempotentRecordDO;
import com.qoder.ride.order.dataobject.OrderDO;
import com.qoder.ride.order.dataobject.RatingDO;
import com.qoder.ride.order.mapper.IdempotentMapper;
import com.qoder.ride.order.mapper.OrderMapper;
import com.qoder.ride.order.mapper.RatingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 订单编排中枢：本地状态机 CAS 保证流转原子性，跨域副作用（计价/派单/支付/推送）
 * 在事务提交后执行，失败由幂等键 + 超时改派 + 支付对账 + BUSY 司机回收调度兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final IdempotentMapper idempotentMapper;
    private final RatingMapper ratingMapper;
    private final TransactionTemplate transactionTemplate;
    private final OrderProperties properties;

    @DubboReference
    private PassengerFacade passengerFacade;
    @DubboReference
    private PricingFacade pricingFacade;
    @DubboReference
    private DriverFacade driverFacade;
    @DubboReference
    private DispatchFacade dispatchFacade;
    @DubboReference
    private PaymentFacade paymentFacade;
    @DubboReference
    private PushFacade pushFacade;
    @DubboReference
    private LocationFacade locationFacade;
    @DubboReference
    private com.qoder.ride.facade.support.SupportFacade supportFacade;

    /** 运营配置（ride-support）覆盖默认参数；support 不可达时回落本地默认，保证交易不中断。 */
    private long cfgLong(String key, long def) {
        try {
            String v = supportFacade.allConfig().orThrow().get(key);
            return v == null || v.isBlank() ? def : Long.parseLong(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private String cfgStr(String key, String def) {
        try {
            String v = supportFacade.allConfig().orThrow().get(key);
            return v == null || v.isBlank() ? def : v.trim();
        } catch (Exception e) {
            return def;
        }
    }

    /** 车型→可接司机车型：拼车/特惠由快车接，优享/豪华由专车接，其余精确匹配（与派单一致）。 */
    private boolean serviceMatches(String orderType, String driverType) {
        if (orderType.equals(driverType)) return true;
        if ("CARPOOL".equals(orderType) || "TEHUI".equals(orderType)) return "EXPRESS".equals(driverType);
        if ("YOUXIANG".equals(orderType) || "LUXURY".equals(orderType)) return "PREMIER".equals(driverType);
        return false;
    }

    /** 多车型同呼：首选车型。 */
    private String primaryServiceType(CallRequest request) {
        if (request.getServiceTypes() != null && !request.getServiceTypes().isEmpty()) {
            return request.getServiceTypes().get(0);
        }
        return request.getServiceType();
    }

    /** 多车型同呼：备选车型（逗号分隔）。 */
    private String fallbackTypesOf(CallRequest request) {
        if (request.getServiceTypes() == null || request.getServiceTypes().size() <= 1) return null;
        return String.join(",", request.getServiceTypes().subList(1, request.getServiceTypes().size()));
    }

    /** 首选无应答超轮次时接力备选车型（重新计价+重置派单），无备选才过期。 */
    private boolean tryServiceFallback(OrderDO order) {
        String fb = order.getFallbackTypes();
        if (fb == null || fb.isBlank()) return false;
        String[] parts = fb.split(",");
        String next = parts[0].trim();
        String rest = parts.length > 1 ? String.join(",", java.util.Arrays.copyOfRange(parts, 1, parts.length)) : null;
        FareDTO fare = quote(order.getOriginLat(), order.getOriginLng(), order.getDestLat(), order.getDestLng(), next, null, null);
        if (orderMapper.switchService(order.getId(), fare.getServiceType(), fare.getTotalFee(), JsonUtil.toJson(fare), fare.getDistanceKm(), rest) == 0) {
            return false;
        }
        log.info("多车型接力 orderId={} next={} rest={}", order.getId(), next, rest);
        OrderDO fresh = require(order.getId());
        pushPassenger(order.getPassengerId(), "SERVICE_FALLBACK", orderBrief(fresh));
        dispatch(fresh, properties.getDefaultRadiusKm());
        return true;
    }

    public OrderDTO call(CallRequest request) {
        if (request.getPassengerId() == null || request.getPassengerId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "乘客 ID 必填");
        }
        requireCoord(request.getOriginLat(), request.getOriginLng());
        requireCoord(request.getDestLat(), request.getDestLng());
        String requestId = blankToUuid(request.getRequestId());

        IdempotentRecordDO existing = idempotentMapper.selectByRequestId(requestId);
        if (existing != null && existing.getResultJson() != null) {
            log.info("叫车幂等命中 requestId={}", requestId);
            return JsonUtil.fromJson(existing.getResultJson(), OrderDTO.class);
        }

        PassengerDTO passenger = passengerFacade.get(request.getPassengerId()).orThrow();
        if (Boolean.TRUE.equals(passenger.getBlocked())) {
            throw new BizException(ResultCode.CONFLICT, "乘客已被封禁，禁止叫车: " + request.getPassengerId());
        }
        FareDTO fare = quote(request.getOriginLat(), request.getOriginLng(),
                request.getDestLat(), request.getDestLng(), primaryServiceType(request), null, null);

        long now = System.currentTimeMillis();
        OrderDO order = new OrderDO();
        order.setId("O" + orderMapper.nextId());
        order.setPassengerId(passenger.getId());
        order.setPassengerName(passenger.getName());
        order.setOriginName(request.getOriginName());
        order.setOriginLat(request.getOriginLat());
        order.setOriginLng(request.getOriginLng());
        order.setDestName(request.getDestName());
        order.setDestLat(request.getDestLat());
        order.setDestLng(request.getDestLng());
        order.setDistanceKm(fare.getDistanceKm());
        order.setEstimateFee(fare.getTotalFee());
        order.setStatus("DISPATCHING");
        order.setRequestId(requestId);
        order.setServiceType(fare.getServiceType());
        order.setFareJson(JsonUtil.toJson(fare));
        order.setFallbackTypes(fallbackTypesOf(request));
        order.setComfort(request.getComfort());
        order.setTipFee(request.getTipFee() == null ? 0L : Math.max(0, request.getTipFee()));
        order.setDispatchRadius(properties.getDefaultRadiusKm());
        order.setLastDispatchAt(now);
        order.setCreatedAt(now);
        OrderDTO dto = toDTO(order);

        try {
            transactionTemplate.executeWithoutResult(s -> {
                orderMapper.insert(order);
                idempotentMapper.insert(new IdempotentRecordDO(requestId, "CALL", JsonUtil.toJson(dto), now));
            });
        } catch (DuplicateKeyException e) {
            IdempotentRecordDO rec = idempotentMapper.selectByRequestId(requestId);
            if (rec != null && rec.getResultJson() != null) {
                return JsonUtil.fromJson(rec.getResultJson(), OrderDTO.class);
            }
            throw new BizException(ResultCode.CONFLICT, "重复叫车请求处理中: " + requestId);
        }
        log.info("乘客叫车 orderId={} passengerId={} serviceType={} estimateFee={} distanceKm={}",
                order.getId(), passenger.getId(), order.getServiceType(), order.getEstimateFee(), order.getDistanceKm());

        dispatch(order, properties.getDefaultRadiusKm());
        pushPassenger(order.getPassengerId(), "ORDER_CREATED", orderBrief(order));
        return dto;
    }

    /**
     * 抢单：先司机 CAS（ONLINE→BUSY）再订单 CAS，订单 CAS 失败回滚司机。
     * 两步之间的崩溃窗口由 reclaimBusyDrivers 调度兜底（宽限期后无活跃单即释放）。
     */
    public OrderDTO accept(String orderId, String driverId) {
        OrderDO order = require(orderId);
        if (!"DISPATCHING".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单已被接单或状态不符: " + orderId);
        }
        DriverDTO driver = driverFacade.get(driverId).orThrow();
        String orderService = order.getServiceType() == null ? "EXPRESS" : order.getServiceType();
        String driverService = driver.getServiceType() == null ? "EXPRESS" : driver.getServiceType();
        if (!serviceMatches(orderService, driverService)) {
            throw new BizException(ResultCode.CONFLICT, "服务类型不匹配: 订单" + orderService + " 司机" + driverService);
        }
        boolean grabbed = Boolean.TRUE.equals(driverFacade.casStatus(driverId, "ONLINE", "BUSY").orThrow());
        if (!grabbed) {
            throw new BizException(ResultCode.CONFLICT, "司机当前不可接单: " + driver.getStatus());
        }
        int rows = orderMapper.accept(orderId, driverId, driver.getName(), driver.getCarNo(), System.currentTimeMillis());
        if (rows == 0) {
            safeCasDriverStatus(driverId, "BUSY", "ONLINE");
            throw new BizException(ResultCode.CONFLICT, "订单已被接单或状态不符: " + orderId);
        }
        log.info("司机接单 orderId={} driverId={}", orderId, driverId);
        OrderDTO dto = get(orderId);
        Map<String, Object> data = orderBrief(order);
        data.put("driverId", driverId);
        data.put("driverName", driver.getName());
        data.put("driverCarNo", driver.getCarNo());
        data.put("serviceScore", driver.getServiceScore());
        pushPassenger(order.getPassengerId(), "DRIVER_ACCEPTED", data);
        pushDriver(driverId, "ORDER_ACCEPTED", data);
        return dto;
    }

    public OrderDTO arrive(String orderId, String driverId) {
        OrderDO order = require(orderId);
        if (orderMapper.arrive(orderId, driverId, System.currentTimeMillis()) == 0) {
            throw new BizException(ResultCode.CONFLICT, "订单状态不符或非本司机: " + orderId);
        }
        log.info("司机到达上车点 orderId={} driverId={}", orderId, driverId);
        pushPassenger(order.getPassengerId(), "DRIVER_ARRIVED", orderBrief(order));
        return get(orderId);
    }

    public OrderDTO startTrip(String orderId, String driverId) {
        OrderDO order = require(orderId);
        if (orderMapper.startTrip(orderId, driverId, System.currentTimeMillis()) == 0) {
            throw new BizException(ResultCode.CONFLICT, "订单状态不符或非本司机: " + orderId);
        }
        log.info("行程开始 orderId={} driverId={}", orderId, driverId);
        pushPassenger(order.getPassengerId(), "TRIP_STARTED", orderBrief(order));
        return get(orderId);
    }

    /** 完单计价：有轨迹按轨迹里程 + 实际时长，无轨迹回落起终点直线估算。 */
    public OrderDTO completeTrip(String orderId, String driverId) {
        OrderDO order = require(orderId);
        double distanceKm;
        long durationMin;
        List<TrackPointDTO> track = safeTrack(orderId);
        if (track.size() >= 2) {
            double sum = 0;
            for (int i = 1; i < track.size(); i++) {
                TrackPointDTO a = track.get(i - 1);
                TrackPointDTO b = track.get(i);
                sum += GeoUtil.distanceKm(a.getLat(), a.getLng(), b.getLat(), b.getLng());
            }
            distanceKm = sum;
            durationMin = Math.max(1, Math.round((track.get(track.size() - 1).getTs() - track.get(0).getTs()) / 60000.0));
        } else {
            distanceKm = GeoUtil.distanceKm(order.getOriginLat(), order.getOriginLng(),
                    order.getDestLat(), order.getDestLng());
            durationMin = GeoUtil.estimateMinutes(distanceKm);
        }
        FareDTO fare = quote(order.getOriginLat(), order.getOriginLng(), order.getDestLat(), order.getDestLng(),
                order.getServiceType(), distanceKm, durationMin);
        if (orderMapper.completeTrip(orderId, driverId, fare.getTotalFee(),
                Math.round(distanceKm * 100.0) / 100.0, durationMin, JsonUtil.toJson(fare), System.currentTimeMillis()) == 0) {
            throw new BizException(ResultCode.CONFLICT, "订单状态不符或非本司机: " + orderId);
        }
        log.info("行程结束 orderId={} driverId={} actualFee={} distanceKm={} durationMin={} trackPoints={}",
                orderId, driverId, fare.getTotalFee(), distanceKm, durationMin, track.size());
        long tip = order.getTipFee() == null ? 0 : order.getTipFee();
        if (tip > 0) {
            try {
                TransferRequest t = new TransferRequest();
                t.setFromUserId(order.getPassengerId());
                t.setToUserId(driverId);
                t.setAmount(tip);
                t.setOrderId(orderId);
                t.setBizType("TIP");
                t.setRequestId("TIP-" + orderId);
                paymentFacade.transfer(t);
                log.info("加价调度费划转 orderId={} tip={}", orderId, tip);
            } catch (Exception e) {
                log.warn("加价调度费划转失败（平台让利）orderId={}: {}", orderId, e.getMessage());
            }
        }
        OrderDTO dto = get(orderId);
        Map<String, Object> data = orderBrief(order);
        data.put("actualFee", fare.getTotalFee());
        data.put("actualDistanceKm", Math.round(distanceKm * 100.0) / 100.0);
        data.put("actualDurationMin", durationMin);
        pushPassenger(order.getPassengerId(), "TRIP_COMPLETED", data);
        pushDriver(driverId, "TRIP_COMPLETED", data);
        return dto;
    }

    /**
     * 订单维度幂等：key=PAY-{orderId}，同一订单任意重试/并发只扣款一次。
     * 支付域事务与订单域事务之间的崩溃窗口由 reconcilePaid 对账兜底。
     */
    public OrderDTO pay(String orderId) {
        return pay(orderId, null);
    }

    public OrderDTO pay(String orderId, String channel) {
        if (channel != null && !channel.isBlank()) {
            orderMapper.updatePayChannel(orderId, channel);
        }
        OrderDO order = require(orderId);
        String key = payKey(orderId);
        IdempotentRecordDO existing = idempotentMapper.selectByRequestId(key);
        if (existing != null && existing.getResultJson() != null) {
            log.info("支付幂等命中 orderId={}", orderId);
            return JsonUtil.fromJson(existing.getResultJson(), OrderDTO.class);
        }
        if ("PAID".equals(order.getStatus())) {
            return toDTO(order);
        }
        if (!"COMPLETED".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单未结束，不可支付: " + order.getStatus());
        }
        PayRequest payRequest = new PayRequest();
        payRequest.setOrderId(orderId);
        payRequest.setPassengerId(order.getPassengerId());
        payRequest.setDriverId(order.getDriverId());
        payRequest.setAmount(order.getActualFee());
        payRequest.setRequestId(key);
        paymentFacade.pay(payRequest).orThrow();
        return finalizePaid(order);
    }

    /** outbox 事件入口：支付域已扣款的权威通知，COMPLETED 则完结，其余状态幂等无操作。 */
    public OrderDTO onPaySucceeded(String orderId) {
        OrderDO order = require(orderId);
        if ("COMPLETED".equals(order.getStatus())) {
            log.info("outbox 事件完结订单 orderId={}", orderId);
            return finalizePaid(order);
        }
        return toDTO(order);
    }

    /** 幂等完结：订单 CAS + MERGE 幂等记录同事务；幂等重放跳过推送（客户端周期同步兜底），司机实收以支付域为准。 */
    private OrderDTO finalizePaid(OrderDO order) {
        String orderId = order.getId();
        long now = System.currentTimeMillis();
        boolean[] shouldPush = {false};
        transactionTemplate.executeWithoutResult(s -> {
            int rows = orderMapper.pay(orderId, now);
            shouldPush[0] = rows > 0 || idempotentMapper.selectByRequestId(payKey(orderId)) == null;
            OrderDTO dto = toDTO(orderMapper.selectById(orderId));
            idempotentMapper.merge(new IdempotentRecordDO(payKey(orderId), "PAY", JsonUtil.toJson(dto), now));
        });
        safeSetDriverStatus(order.getDriverId(), "ONLINE");
        if (shouldPush[0]) {
            log.info("订单支付完结 orderId={} amount={}", orderId, order.getActualFee());
            Map<String, Object> data = orderBrief(order);
            data.put("status", "PAID");
            data.put("actualFee", order.getActualFee());
            PayResultDTO payResult = safePayResult(orderId);
            if (payResult != null) {
                data.put("driverIncome", payResult.getDriverIncome());
                data.put("fee", payResult.getFee());
            }
            pushPassenger(order.getPassengerId(), "ORDER_PAID", data);
            pushDriver(order.getDriverId(), "ORDER_PAID", data);
        }
        return get(orderId);
    }

    private PayResultDTO safePayResult(String orderId) {
        try {
            return paymentFacade.payResult(orderId).orThrow();
        } catch (Exception e) {
            log.warn("支付结果快照查询失败 orderId={}: {}", orderId, e.getMessage());
            return null;
        }
    }

    /**
     * 乘客取消：ACCEPTED 超免费窗口或 ARRIVED 后收取消费（全额归司机，CANCEL-{orderId} 幂等）。
     * 先 CAS 取消（权威），取消费划转失败不影响取消（余额不足等场景平台让利，流水可审计）。
     */
    public OrderDTO cancel(String orderId, String reason) {
        OrderDO order = require(orderId);
        long fee = 0;
        if (order.getDriverId() != null) {
            long feeAmount = cfgLong("cancel.feeAmount", properties.getCancelFeeAmount());
            long freeWindowMs = cfgLong("cancel.freeWindowSec", 120) * 1000;
            if ("ARRIVED".equals(order.getStatus())) {
                fee = feeAmount;
            } else if ("ACCEPTED".equals(order.getStatus()) && order.getAcceptedAt() != null
                    && System.currentTimeMillis() - order.getAcceptedAt() > freeWindowMs) {
                fee = feeAmount;
            }
        }
        if (orderMapper.cancel(orderId, reason, fee, System.currentTimeMillis()) == 0) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可取消: " + order.getStatus());
        }
        if (order.getDriverId() != null && ("ACCEPTED".equals(order.getStatus()) || "ARRIVED".equals(order.getStatus()))) {
            safeSetDriverStatus(order.getDriverId(), "ONLINE");
        }
        if (fee > 0) {
            fee = chargeCancelFee(order, fee);
        }
        log.info("订单取消 orderId={} reason={} cancelFee={}", orderId, reason, fee);
        Map<String, Object> data = orderBrief(order);
        data.put("cancelFee", fee);
        data.put("cancelReason", reason);
        pushPassenger(order.getPassengerId(), "ORDER_CANCELLED", data);
        if (order.getDriverId() != null) {
            pushDriver(order.getDriverId(), "ORDER_CANCELLED", data);
        }
        return get(orderId);
    }

    private long chargeCancelFee(OrderDO order, long fee) {
        try {
            TransferRequest transfer = new TransferRequest();
            transfer.setFromUserId(order.getPassengerId());
            transfer.setToUserId(order.getDriverId());
            transfer.setAmount(fee);
            transfer.setOrderId(order.getId());
            transfer.setRequestId("CANCEL-" + order.getId());
            transfer.setBizType("CANCEL_FEE");
            paymentFacade.transfer(transfer).orThrow();
            return fee;
        } catch (Exception e) {
            log.warn("取消费划转失败（取消不受影响）orderId={}: {}", order.getId(), e.getMessage());
            orderMapper.updateCancelFee(order.getId(), 0);
            return 0;
        }
    }

    /** 司机取消：订单回 DISPATCHING 重新派单，排除该司机，司机回 ONLINE。 */
    public OrderDTO driverCancel(String orderId, String driverId, String reason) {
        OrderDO order = require(orderId);
        String declined = appendId(order.getDeclinedDriverIds(), driverId);
        if (orderMapper.driverCancel(orderId, driverId, reason, properties.getDefaultRadiusKm(), declined) == 0) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可司机取消或非本司机: " + orderId);
        }
        long waitFee = 0;
        if ("ARRIVED".equals(order.getStatus()) && order.getArrivedAt() != null
                && System.currentTimeMillis() - order.getArrivedAt() > cfgLong("cancel.freeWaitSec", 300) * 1000) {
            waitFee = chargeCancelFee(order, cfgLong("cancel.feeAmount", properties.getCancelFeeAmount()));
            orderMapper.updateCancelFee(orderId, waitFee);
            log.info("司机等待超时补偿 orderId={} driverId={} fee={}", orderId, driverId, waitFee);
        }
        safeSetDriverStatus(driverId, "ONLINE");
        log.info("司机取消订单 orderId={} driverId={} reason={}", orderId, driverId, reason);
        OrderDO fresh = require(orderId);
        Map<String, Object> data = orderBrief(fresh);
        data.put("cancelReason", reason);
        data.put("byDriver", true);
        data.put("cancelFee", waitFee);
        pushPassenger(order.getPassengerId(), "DRIVER_CANCELLED", data);
        pushDriver(driverId, "ORDER_CANCELLED", data);
        dispatch(fresh, properties.getDefaultRadiusKm());
        return toDTO(fresh);
    }

    /** 司机拒绝邀约：记拒单并排除后续邀约；指派模式立即顺延下一位。 */
    public void declineOffer(String orderId, String driverId) {
        OrderDO order = require(orderId);
        if (!"DISPATCHING".equals(order.getStatus())) {
            return;
        }
        orderMapper.updateDeclined(orderId, appendId(order.getDeclinedDriverIds(), driverId));
        try {
            driverFacade.recordDecline(driverId).orThrow();
        } catch (Exception e) {
            log.warn("拒单计数失败 driverId={}: {}", driverId, e.getMessage());
        }
        log.info("司机拒绝邀约 orderId={} driverId={}", orderId, driverId);
        if (isAssignMode()) {
            dispatch(require(orderId), order.getDispatchRadius() == null
                    ? properties.getDefaultRadiusKm() : order.getDispatchRadius());
        }
    }

    public RatingDTO rate(RatingRequest request) {
        if (request.getOrderId() == null || request.getOrderId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "订单 ID 必填");
        }
        if (!"passenger".equals(request.getRole()) && !"driver".equals(request.getRole())) {
            throw new BizException(ResultCode.PARAM_ERROR, "评价角色须为 passenger/driver");
        }
        if (request.getScore() == null || request.getScore() < 1 || request.getScore() > 5) {
            throw new BizException(ResultCode.PARAM_ERROR, "评分须为 1~5 星");
        }
        OrderDO order = require(request.getOrderId());
        if (!"PAID".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "订单支付完成后方可评价: " + order.getStatus());
        }
        long now = System.currentTimeMillis();
        RatingDO rating = new RatingDO();
        rating.setOrderId(request.getOrderId());
        rating.setRole(request.getRole());
        rating.setScore(request.getScore());
        rating.setComment(request.getComment());
        rating.setTags(request.getTags());
        rating.setAnonymous(Boolean.TRUE.equals(request.getAnonymous()));
        rating.setCreatedAt(now);
        try {
            ratingMapper.insert(rating);
        } catch (DuplicateKeyException e) {
            RatingDO existing = ratingMapper.selectByOrderAndRole(request.getOrderId(), request.getRole());
            if (existing != null) {
                return toRatingDTO(existing);
            }
            throw new BizException(ResultCode.CONFLICT, "重复评价处理中");
        }
        log.info("行程评价 orderId={} role={} score={}", request.getOrderId(), request.getRole(), request.getScore());
        if ("passenger".equals(request.getRole()) && order.getDriverId() != null) {
            Double avg = ratingMapper.avgScoreForDriver(order.getDriverId());
            if (avg != null) {
                try {
                    driverFacade.updateScore(order.getDriverId(), Math.round(avg * 100.0) / 100.0).orThrow();
                } catch (Exception e) {
                    log.warn("司机服务分更新失败 driverId={}: {}", order.getDriverId(), e.getMessage());
                }
            }
            Map<String, Object> data = orderBrief(order);
            data.put("score", request.getScore());
            pushDriver(order.getDriverId(), "ORDER_RATED", data);
        } else if ("driver".equals(request.getRole())) {
            Map<String, Object> data = orderBrief(order);
            data.put("score", request.getScore());
            pushPassenger(order.getPassengerId(), "ORDER_RATED", data);
        }
        return toRatingDTO(rating);
    }

    public List<RatingDTO> ratings(String orderId) {
        require(orderId);
        return ratingMapper.selectByOrder(orderId).stream().map(this::toRatingDTO).toList();
    }

    /**
     * 管理端退款（支持部分退款）：先反向划转（REFUND-{orderId}-{seq} 幂等），再按支付域
     * 累计退款同步订单状态；崩溃窗口由退款 outbox + 对账兜底。退满置 REFUNDED，部分退保持 PAID。
     */
    public OrderDTO refund(String orderId, String reason, Long amount) {
        OrderDO order = require(orderId);
        if ("REFUNDED".equals(order.getStatus())) {
            return toDTO(order);
        }
        if (!"PAID".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅已支付订单可退款: " + order.getStatus());
        }
        long refunded = order.getRefundedAmount() == null ? 0 : order.getRefundedAmount();
        long remaining = order.getActualFee() - refunded;
        long amt = amount == null ? remaining : amount;
        if (amt <= 0 || amt > remaining) {
            throw new BizException(ResultCode.PARAM_ERROR, "退款金额需为 1~剩余可退 " + remaining + "（分）");
        }
        int seq = (order.getRefundCount() == null ? 0 : order.getRefundCount()) + 1;
        RefundRequest refundRequest = new RefundRequest();
        refundRequest.setOrderId(orderId);
        refundRequest.setPassengerId(order.getPassengerId());
        refundRequest.setDriverId(order.getDriverId());
        refundRequest.setAmount(amt);
        refundRequest.setRequestId("REFUND-" + orderId + "-" + seq);
        PayResultDTO result = paymentFacade.refund(refundRequest).orThrow();
        if (Boolean.TRUE.equals(result.getRepeated()) && result.getAmount() != null && result.getAmount() != amt) {
            throw new BizException(ResultCode.CONFLICT, "退款序号已被其他金额占用，请重试: " + orderId);
        }
        return syncRefundState(order, reason);
    }

    /** 退款 outbox 事件入口：按支付域累计退款同步订单状态（幂等，非 PAID 无操作）。 */
    public OrderDTO onRefundSucceeded(String orderId) {
        OrderDO order = require(orderId);
        if ("PAID".equals(order.getStatus())) {
            return syncRefundState(order, "退款事件同步");
        }
        return toDTO(order);
    }

    /** 支付域退款流水为权威：同步累计退款额/笔数，退满 finalizeRefund，状态变化才推送。 */
    private OrderDTO syncRefundState(OrderDO order, String reason) {
        String orderId = order.getId();
        RefundSummaryDTO summary = paymentFacade.refundSummary(orderId).orThrow();
        long total = summary.getTotalAmount() == null ? 0 : summary.getTotalAmount();
        int count = summary.getCount() == null ? 0 : summary.getCount();
        long before = order.getRefundedAmount() == null ? 0 : order.getRefundedAmount();
        int changed = orderMapper.syncRefund(orderId, total, count);
        OrderDO fresh = require(orderId);
        if (total >= fresh.getActualFee()) {
            return finalizeRefund(fresh, reason, total - before);
        }
        if (changed > 0) {
            log.info("订单部分退款 orderId={} 本次={} 累计={}/{}", orderId, total - before, total, fresh.getActualFee());
            Map<String, Object> data = orderBrief(fresh);
            data.put("status", "PAID");
            data.put("refundAmount", total - before);
            data.put("refundedTotal", total);
            data.put("remaining", fresh.getActualFee() - total);
            data.put("partial", true);
            pushPassenger(fresh.getPassengerId(), "ORDER_REFUNDED", data);
            pushDriver(fresh.getDriverId(), "ORDER_REFUNDED", data);
        }
        return toDTO(fresh);
    }

    /** 幂等终态：CAS + MERGE 幂等记录同事务，重放跳过推送（客户端周期同步兜底）。 */
    private OrderDTO finalizeRefund(OrderDO order, String reason, long delta) {
        String orderId = order.getId();
        long now = System.currentTimeMillis();
        String key = "REFUND-FINAL-" + orderId;
        boolean[] shouldPush = {false};
        transactionTemplate.executeWithoutResult(s -> {
            int rows = orderMapper.refund(orderId, reason, now);
            shouldPush[0] = rows > 0 || idempotentMapper.selectByRequestId(key) == null;
            OrderDTO dto = toDTO(orderMapper.selectById(orderId));
            idempotentMapper.merge(new IdempotentRecordDO(key, "REFUND", JsonUtil.toJson(dto), now));
        });
        if (shouldPush[0]) {
            log.info("订单退款完结 orderId={} refundedTotal={}", orderId, order.getActualFee());
            Map<String, Object> data = orderBrief(order);
            data.put("status", "REFUNDED");
            data.put("refundAmount", delta);
            data.put("refundedTotal", order.getActualFee());
            data.put("partial", false);
            pushPassenger(order.getPassengerId(), "ORDER_REFUNDED", data);
            pushDriver(order.getDriverId(), "ORDER_REFUNDED", data);
        }
        return get(orderId);
    }

    /** 管理端强制取消：含行程中的异常单关闭，释放司机。 */
    public OrderDTO forceCancel(String orderId, String reason) {
        OrderDO order = require(orderId);
        if (orderMapper.forceCancel(orderId, reason, System.currentTimeMillis()) == 0) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可强制取消: " + order.getStatus());
        }
        if (order.getDriverId() != null && !"DISPATCHING".equals(order.getStatus())) {
            safeSetDriverStatus(order.getDriverId(), "ONLINE");
        }
        log.warn("管理端强制取消 orderId={} reason={}", orderId, reason);
        Map<String, Object> data = orderBrief(order);
        data.put("cancelReason", reason);
        data.put("forced", true);
        pushPassenger(order.getPassengerId(), "ORDER_CANCELLED", data);
        pushDriver(order.getDriverId(), "ORDER_CANCELLED", data);
        return get(orderId);
    }

    public OrderDTO get(String orderId) {
        return toDTO(require(orderId));
    }

    public List<OrderDTO> list() {
        return orderMapper.selectAll().stream().map(this::toDTO).toList();
    }

    public List<OrderDTO> listByPassenger(String passengerId) {
        return orderMapper.selectByPassenger(passengerId).stream().map(this::toDTO).toList();
    }

    public List<OrderDTO> listByDriver(String driverId) {
        return orderMapper.selectByDriver(driverId).stream().map(this::toDTO).toList();
    }

    public List<OrderDTO> listByStatus(String status) {
        return orderMapper.selectByStatus(status).stream().map(this::toDTO).toList();
    }

    public PageDTO<OrderDTO> query(OrderQuery query) {
        int page = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int size = query.getSize() == null || query.getSize() < 1 ? 20 : Math.min(query.getSize(), 100);
        Map<String, Object> params = new HashMap<>();
        if (query.getPassengerId() != null && !query.getPassengerId().isBlank()) {
            params.put("passengerId", query.getPassengerId());
        }
        if (query.getDriverId() != null && !query.getDriverId().isBlank()) {
            params.put("driverId", query.getDriverId());
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            params.put("status", query.getStatus());
        }
        params.put("from", query.getFrom());
        params.put("to", query.getTo());
        params.put("size", size);
        params.put("offset", (page - 1) * size);
        long total = orderMapper.count(params);
        List<OrderDTO> items = orderMapper.query(params).stream().map(this::toDTO).toList();
        return PageDTO.of(items, total, page, size);
    }

    public OrderStatsDTO stats() {
        OrderStatsDTO dto = new OrderStatsDTO();
        long total = orderMapper.count(new HashMap<>());
        dto.setTotalOrders(total);
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : orderMapper.statusCounts()) {
            statusCounts.put(String.valueOf(row.get("status")), ((Number) row.get("cnt")).longValue());
        }
        dto.setStatusCounts(statusCounts);
        long todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        dto.setTodayOrders(orderMapper.countSince(todayStart));
        dto.setTodayPaid(orderMapper.paidSince(todayStart));
        dto.setTodayGmv(orderMapper.gmvSince(todayStart));
        dto.setTotalGmv(orderMapper.totalGmv());
        dto.setAvgAcceptMs(orderMapper.avgAcceptMs());
        dto.setAcceptRate(total == 0 ? null : (double) orderMapper.everAccepted() / total);
        return dto;
    }

    /** 超时改派：DISPATCHING 超轮次置 EXPIRED；超时扩半径（grab）或顺延下一位（assign）。 */
    @Scheduled(fixedDelayString = "${order.redispatch-interval-ms:5000}")
    public void redispatch() {
        long now = System.currentTimeMillis();
        for (OrderDO order : orderMapper.selectByStatus("DISPATCHING")) {
            int round = order.getDispatchRound() == null ? 0 : order.getDispatchRound();
            if (round >= properties.getMaxDispatchRounds()) {
                if (tryServiceFallback(order)) {
                    continue;
                }
                if (orderMapper.expire(order.getId(), now) > 0) {
                    log.warn("无人接单超轮次，订单过期 orderId={} rounds={}", order.getId(), round);
                    pushPassenger(order.getPassengerId(), "ORDER_EXPIRED", orderBrief(order));
                }
                continue;
            }
            Long last = order.getLastDispatchAt();
            long timeout = isAssignMode() ? properties.getOfferTtlMs() : properties.getDispatchTimeoutMs();
            if (last != null && now - last < timeout) {
                continue;
            }
            double current = order.getDispatchRadius() == null ? properties.getDefaultRadiusKm() : order.getDispatchRadius();
            double next = isAssignMode() ? current
                    : Math.min(properties.getMaxRadiusKm(), current + properties.getRadiusStepKm());
            log.info("超时改派 orderId={} mode={} radius={}→{}", order.getId(), properties.getDispatchMode(), current, next);
            dispatch(order, next);
        }
    }

    /**
     * 支付/退款对账：支付域流水与动账同事务提交（流水在即钱已动），订单未同步的崩溃窗口
     * 宽限期后自动完结/同步，进程重启后自愈，不依赖客户端重试。顺带催付与免密自动扣款。
     */
    @Scheduled(fixedDelayString = "${order.reconcile-interval-ms:5000}")
    public void reconcilePaid() {
        long now = System.currentTimeMillis();
        for (OrderDO order : orderMapper.selectByStatus("COMPLETED")) {
            Long completedAt = order.getCompletedAt();
            if (completedAt == null) {
                continue;
            }
            remindOrAutoPay(order, now);
            if (now - completedAt < properties.getReconcileGraceMs()) {
                continue;
            }
            try {
                if (Boolean.TRUE.equals(paymentFacade.isPaid(order.getId()).orThrow())) {
                    log.warn("对账发现已扣款未完结订单，自动完结 orderId={}", order.getId());
                    finalizePaid(order);
                }
            } catch (Exception e) {
                log.warn("支付对账失败 orderId={}: {}", order.getId(), e.getMessage());
            }
        }
        for (OrderDO order : orderMapper.selectByStatus("PAID")) {
            Long paidAt = order.getPaidAt();
            if (paidAt == null || now - paidAt < properties.getReconcileGraceMs()) {
                continue;
            }
            try {
                RefundSummaryDTO summary = paymentFacade.refundSummary(order.getId()).orThrow();
                long total = summary.getTotalAmount() == null ? 0 : summary.getTotalAmount();
                int count = summary.getCount() == null ? 0 : summary.getCount();
                long refunded = order.getRefundedAmount() == null ? 0 : order.getRefundedAmount();
                int refundCount = order.getRefundCount() == null ? 0 : order.getRefundCount();
                if (total > refunded || count > refundCount) {
                    log.warn("对账发现退款未同步订单，自动同步 orderId={} refundedTotal={}", order.getId(), total);
                    syncRefundState(order, "退款对账自动完结");
                }
            } catch (Exception e) {
                log.warn("退款对账失败 orderId={}: {}", order.getId(), e.getMessage());
            }
        }
    }

    /** 催付：超阈值未付推送一次；授权乘客宽限期到自动扣款，失败转催付并按间隔重试。 */
    private void remindOrAutoPay(OrderDO order, long now) {
        long elapsed = now - order.getCompletedAt();
        boolean autoPay = false;
        try {
            PassengerDTO passenger = passengerFacade.get(order.getPassengerId()).orThrow();
            autoPay = passenger != null && Boolean.TRUE.equals(passenger.getAutoPay());
        } catch (Exception e) {
            log.warn("免密授权查询失败 passengerId={}: {}", order.getPassengerId(), e.getMessage());
        }
        Long remindedAt = order.getRemindedAt();
        if (autoPay && elapsed >= properties.getAutoPayDelayMs()
                && (remindedAt == null || now - remindedAt >= properties.getAutoPayRetryMs())) {
            try {
                pay(order.getId());
                log.info("免密自动扣款成功 orderId={}", order.getId());
                return;
            } catch (Exception e) {
                log.warn("免密自动扣款失败，转催付 orderId={}: {}", order.getId(), e.getMessage());
                pushReminder(order, now);
                return;
            }
        }
        if (remindedAt == null && elapsed >= properties.getRemindAfterMs()) {
            pushReminder(order, now);
        }
    }

    private void pushReminder(OrderDO order, long now) {
        Map<String, Object> data = orderBrief(order);
        data.put("actualFee", order.getActualFee());
        pushPassenger(order.getPassengerId(), "PAYMENT_REMINDER", data);
        orderMapper.markReminded(order.getId(), now);
        log.info("支付催付 orderId={}", order.getId());
    }

    /**
     * BUSY 司机回收：抢单司机 CAS 与订单 CAS 之间崩溃会留下无活跃单的 BUSY 司机，
     * 宽限期后仍无活跃单则释放回 ONLINE（服务端兜底，不依赖客户端）。
     */
    @Scheduled(fixedDelayString = "${order.driver-reclaim-interval-ms:10000}")
    public void reclaimBusyDrivers() {
        long now = System.currentTimeMillis();
        List<DriverDTO> drivers;
        try {
            drivers = driverFacade.list().orThrow();
        } catch (Exception e) {
            log.warn("BUSY 司机回收：司机域不可达: {}", e.getMessage());
            return;
        }
        for (DriverDTO driver : drivers) {
            if (!"BUSY".equals(driver.getStatus())) {
                continue;
            }
            if (driver.getUpdatedAt() != null && now - driver.getUpdatedAt() < properties.getDriverReclaimGraceMs()) {
                continue;
            }
            if (!orderMapper.selectActiveByDriver(driver.getId()).isEmpty()) {
                continue;
            }
            log.warn("回收无活跃单的 BUSY 司机 driverId={}", driver.getId());
            safeCasDriverStatus(driver.getId(), "BUSY", "ONLINE");
        }
    }

    private void dispatch(OrderDO order, double radius) {
        long cfgRadius = cfgLong("dispatch.radiusKm", 0);
        double effRadius = cfgRadius > 0 ? Math.max(radius, cfgRadius) : radius;
        orderMapper.markDispatched(order.getId(), System.currentTimeMillis(), effRadius);
        DispatchRequest request = new DispatchRequest();
        request.setOrderId(order.getId());
        request.setPassengerId(order.getPassengerId());
        request.setPassengerName(order.getPassengerName());
        request.setOriginName(order.getOriginName());
        request.setOriginLat(order.getOriginLat());
        request.setOriginLng(order.getOriginLng());
        request.setDestName(order.getDestName());
        request.setDestLat(order.getDestLat());
        request.setDestLng(order.getDestLng());
        request.setEstimateFee(order.getEstimateFee());
        request.setDistanceKm(order.getDistanceKm());
        request.setRadiusKm(effRadius);
        request.setMode(cfgStr("dispatch.mode", properties.getDispatchMode()));
        request.setOfferExpireAt(System.currentTimeMillis() + cfgLong("dispatch.offerTtlSec", 15) * 1000);
        request.setServiceType(order.getServiceType());
        Set<String> excluded = splitIds(order.getDeclinedDriverIds());
        if (isAssignMode()) {
            excluded.addAll(splitIds(order.getOfferedDriverIds()));
        }
        request.setExcludeDriverIds(String.join(",", excluded));
        try {
            DispatchResultDTO result = dispatchFacade.dispatch(request).orThrow();
            if (result.getOfferedDriverIds() != null && !result.getOfferedDriverIds().isEmpty()) {
                Set<String> offered = splitIds(order.getOfferedDriverIds());
                offered.addAll(result.getOfferedDriverIds());
                orderMapper.updateOffered(order.getId(), String.join(",", offered));
            }
        } catch (Exception e) {
            log.warn("派单失败，等待改派重试 orderId={}: {}", order.getId(), e.getMessage());
        }
    }

    private boolean isAssignMode() {
        return "assign".equalsIgnoreCase(cfgStr("dispatch.mode", properties.getDispatchMode()));
    }

    private FareDTO quote(double originLat, double originLng, double destLat, double destLng,
                          String serviceType, Double distanceOverride, Long durationOverride) {
        FareRequest request = new FareRequest();
        request.setOriginLat(originLat);
        request.setOriginLng(originLng);
        request.setDestLat(destLat);
        request.setDestLng(destLng);
        request.setServiceType(serviceType);
        request.setDistanceKmOverride(distanceOverride);
        request.setDurationMinOverride(durationOverride);
        return pricingFacade.quote(request).orThrow();
    }

    private List<TrackPointDTO> safeTrack(String orderId) {
        try {
            List<TrackPointDTO> track = locationFacade.track(orderId).orThrow();
            return track == null ? List.of() : track;
        } catch (Exception e) {
            log.warn("轨迹查询失败，回落直线计价 orderId={}: {}", orderId, e.getMessage());
            return List.of();
        }
    }

    private void safeSetDriverStatus(String driverId, String status) {
        if (driverId == null) {
            return;
        }
        try {
            driverFacade.setStatus(driverId, status).orThrow();
        } catch (Exception e) {
            log.warn("司机状态变更失败 driverId={} status={}: {}", driverId, status, e.getMessage());
        }
    }

    private void safeCasDriverStatus(String driverId, String from, String to) {
        if (driverId == null) {
            return;
        }
        try {
            driverFacade.casStatus(driverId, from, to).orThrow();
        } catch (Exception e) {
            log.warn("司机状态 CAS 失败 driverId={} {}→{}: {}", driverId, from, to, e.getMessage());
        }
    }

    private void pushPassenger(String passengerId, String event, Map<String, Object> data) {
        try {
            pushFacade.pushToPassenger(passengerId, event, data);
        } catch (Exception e) {
            log.warn("推送乘客失败 passengerId={} event={}: {}", passengerId, event, e.getMessage());
        }
    }

    private void pushDriver(String driverId, String event, Map<String, Object> data) {
        if (driverId == null) {
            return;
        }
        try {
            pushFacade.pushToDriver(driverId, event, data);
        } catch (Exception e) {
            log.warn("推送司机失败 driverId={} event={}: {}", driverId, event, e.getMessage());
        }
    }

    private Map<String, Object> orderBrief(OrderDO order) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("passengerId", order.getPassengerId());
        data.put("passengerName", order.getPassengerName());
        data.put("originName", order.getOriginName());
        data.put("destName", order.getDestName());
        data.put("estimateFee", order.getEstimateFee());
        data.put("status", order.getStatus());
        data.put("serviceType", order.getServiceType());
        return data;
    }

    private OrderDO require(String orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ResultCode.NOT_FOUND, "订单不存在: " + orderId);
        }
        return order;
    }

    private void requireCoord(Double lat, Double lng) {
        if (lat == null || lng == null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new BizException(ResultCode.PARAM_ERROR, "经纬度非法");
        }
    }

    private String payKey(String orderId) {
        return "PAY-" + orderId;
    }

    private String blankToUuid(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString().replace("-", "") : value;
    }

    private String appendId(String csv, String id) {
        Set<String> ids = splitIds(csv);
        ids.add(id);
        return String.join(",", ids);
    }

    private Set<String> splitIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** 行程中修改目的地：重新计价更新预估，推送司乘。 */
    public OrderDTO modifyDest(String orderId, String destName, Double destLat, Double destLng) {
        OrderDO order = require(orderId);
        if (!"ACCEPTED".equals(order.getStatus()) && !"ARRIVED".equals(order.getStatus()) && !"IN_PROGRESS".equals(order.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "当前状态不可修改目的地: " + order.getStatus());
        }
        requireCoord(destLat, destLng);
        FareDTO fare = quote(order.getOriginLat(), order.getOriginLng(), destLat, destLng, order.getServiceType(), null, null);
        if (orderMapper.updateDest(orderId, destName, destLat, destLng, fare.getDistanceKm(), fare.getTotalFee(), JsonUtil.toJson(fare)) == 0) {
            throw new BizException(ResultCode.CONFLICT, "修改目的地失败: " + orderId);
        }
        OrderDO fresh = require(orderId);
        log.info("修改目的地 orderId={} dest={} newEstimate={}", orderId, destName, fare.getTotalFee());
        pushPassenger(order.getPassengerId(), "DEST_CHANGED", orderBrief(fresh));
        if (order.getDriverId() != null) pushDriver(order.getDriverId(), "DEST_CHANGED", orderBrief(fresh));
        return toDTO(fresh);
    }

    /** 司机口碑聚合：近期已完成订单的乘客评价（最多 50 条）。 */
    public List<RatingDTO> driverRatings(String driverId) {
        List<RatingDTO> out = new java.util.ArrayList<>();
        for (OrderDO o : orderMapper.selectByDriver(driverId)) {
            if (o.getActualFee() == null) continue;
            for (RatingDO r : ratingMapper.selectByOrder(o.getId())) {
                if ("passenger".equals(r.getRole())) out.add(toRatingDTO(r));
            }
            if (out.size() >= 50) break;
        }
        return out;
    }

    private RatingDTO toRatingDTO(RatingDO rating) {
        RatingDTO dto = new RatingDTO();
        dto.setOrderId(rating.getOrderId());
        dto.setRole(rating.getRole());
        dto.setScore(rating.getScore());
        dto.setComment(rating.getComment());
        dto.setTags(rating.getTags());
        dto.setAnonymous(Boolean.TRUE.equals(rating.getAnonymous()));
        dto.setCreatedAt(rating.getCreatedAt());
        return dto;
    }

    private OrderDTO toDTO(OrderDO order) {
        OrderDTO dto = new OrderDTO();
        dto.setId(order.getId());
        dto.setPassengerId(order.getPassengerId());
        dto.setPassengerName(order.getPassengerName());
        dto.setDriverId(order.getDriverId());
        dto.setDriverName(order.getDriverName());
        dto.setDriverCarNo(order.getDriverCarNo());
        dto.setOriginName(order.getOriginName());
        dto.setOriginLat(order.getOriginLat());
        dto.setOriginLng(order.getOriginLng());
        dto.setDestName(order.getDestName());
        dto.setDestLat(order.getDestLat());
        dto.setDestLng(order.getDestLng());
        dto.setDistanceKm(order.getDistanceKm());
        dto.setEstimateFee(order.getEstimateFee());
        dto.setActualFee(order.getActualFee());
        dto.setStatus(order.getStatus());
        dto.setRequestId(order.getRequestId());
        dto.setCancelReason(order.getCancelReason());
        dto.setServiceType(order.getServiceType() == null ? "EXPRESS" : order.getServiceType());
        dto.setComfort(order.getComfort());
        dto.setTipFee(order.getTipFee() == null ? 0L : order.getTipFee());
        dto.setPayChannel(order.getPayChannel());
        if (order.getFareJson() != null) {
            try {
                dto.setFare(JsonUtil.fromJson(order.getFareJson(), FareDTO.class));
            } catch (Exception e) {
                log.warn("计价快照解析失败 orderId={}", order.getId());
            }
        }
        if (order.getActualFareJson() != null) {
            try {
                dto.setActualFare(JsonUtil.fromJson(order.getActualFareJson(), FareDTO.class));
            } catch (Exception e) {
                log.warn("实际计价明细解析失败 orderId={}", order.getId());
            }
        }
        dto.setRefundedAmount(order.getRefundedAmount() == null ? 0L : order.getRefundedAmount());
        dto.setRefundCount(order.getRefundCount() == null ? 0 : order.getRefundCount());
        dto.setActualDistanceKm(order.getActualDistanceKm());
        dto.setActualDurationMin(order.getActualDurationMin());
        dto.setDispatchRound(order.getDispatchRound());
        dto.setOfferedDriverIds(order.getOfferedDriverIds());
        dto.setDeclinedDriverIds(order.getDeclinedDriverIds());
        dto.setCancelFee(order.getCancelFee());
        dto.setCreatedAt(order.getCreatedAt());
        dto.setAcceptedAt(order.getAcceptedAt());
        dto.setArrivedAt(order.getArrivedAt());
        dto.setStartedAt(order.getStartedAt());
        dto.setCompletedAt(order.getCompletedAt());
        dto.setPaidAt(order.getPaidAt());
        dto.setCancelledAt(order.getCancelledAt());
        dto.setExpiredAt(order.getExpiredAt());
        dto.setRefundedAt(order.getRefundedAt());
        return dto;
    }
}