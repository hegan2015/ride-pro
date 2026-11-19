package com.qoder.ride.order.facade;

import com.qoder.ride.common.result.FacadeSupport;
import com.qoder.ride.common.result.Result;
import com.qoder.ride.facade.common.PageDTO;
import com.qoder.ride.facade.order.CallRequest;
import com.qoder.ride.facade.order.OrderDTO;
import com.qoder.ride.facade.order.OrderFacade;
import com.qoder.ride.facade.order.OrderQuery;
import com.qoder.ride.facade.order.OrderStatsDTO;
import com.qoder.ride.facade.order.RatingDTO;
import com.qoder.ride.facade.order.RatingRequest;
import com.qoder.ride.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class OrderFacadeImpl implements OrderFacade {

    private final OrderService orderService;

    @Override
    public Result<OrderDTO> call(CallRequest request) {
        return FacadeSupport.execute(() -> orderService.call(request));
    }

    @Override
    public Result<OrderDTO> accept(String orderId, String driverId) {
        return FacadeSupport.execute(() -> orderService.accept(orderId, driverId));
    }

    @Override
    public Result<OrderDTO> arrive(String orderId, String driverId) {
        return FacadeSupport.execute(() -> orderService.arrive(orderId, driverId));
    }

    @Override
    public Result<OrderDTO> startTrip(String orderId, String driverId) {
        return FacadeSupport.execute(() -> orderService.startTrip(orderId, driverId));
    }

    @Override
    public Result<OrderDTO> completeTrip(String orderId, String driverId) {
        return FacadeSupport.execute(() -> orderService.completeTrip(orderId, driverId));
    }

    @Override
    public Result<OrderDTO> pay(String orderId) {
        return FacadeSupport.execute(() -> orderService.pay(orderId));
    }

    @Override
    public Result<OrderDTO> pay(String orderId, String channel) {
        return FacadeSupport.execute(() -> orderService.pay(orderId, channel));
    }

    @Override
    public Result<OrderDTO> onPaySucceeded(String orderId) {
        return FacadeSupport.execute(() -> orderService.onPaySucceeded(orderId));
    }

    @Override
    public Result<OrderDTO> cancel(String orderId, String reason) {
        return FacadeSupport.execute(() -> orderService.cancel(orderId, reason));
    }

    @Override
    public Result<OrderDTO> driverCancel(String orderId, String driverId, String reason) {
        return FacadeSupport.execute(() -> orderService.driverCancel(orderId, driverId, reason));
    }

    @Override
    public Result<Void> declineOffer(String orderId, String driverId) {
        return FacadeSupport.execute(() -> {
            orderService.declineOffer(orderId, driverId);
            return null;
        });
    }

    @Override
    public Result<RatingDTO> rate(RatingRequest request) {
        return FacadeSupport.execute(() -> orderService.rate(request));
    }

    @Override
    public Result<List<RatingDTO>> ratings(String orderId) {
        return FacadeSupport.execute(() -> orderService.ratings(orderId));
    }

    @Override
    public Result<List<RatingDTO>> driverRatings(String driverId) {
        return FacadeSupport.execute(() -> orderService.driverRatings(driverId));
    }

    @Override
    public Result<OrderDTO> modifyDest(String orderId, String destName, Double destLat, Double destLng) {
        return FacadeSupport.execute(() -> orderService.modifyDest(orderId, destName, destLat, destLng));
    }

    @Override
    public Result<OrderDTO> refund(String orderId, String reason, Long amount) {
        return FacadeSupport.execute(() -> orderService.refund(orderId, reason, amount));
    }

    @Override
    public Result<OrderDTO> onRefundSucceeded(String orderId) {
        return FacadeSupport.execute(() -> orderService.onRefundSucceeded(orderId));
    }

    @Override
    public Result<OrderDTO> forceCancel(String orderId, String reason) {
        return FacadeSupport.execute(() -> orderService.forceCancel(orderId, reason));
    }

    @Override
    public Result<OrderDTO> get(String orderId) {
        return FacadeSupport.execute(() -> orderService.get(orderId));
    }

    @Override
    public Result<List<OrderDTO>> list() {
        return FacadeSupport.execute(orderService::list);
    }

    @Override
    public Result<List<OrderDTO>> listByPassenger(String passengerId) {
        return FacadeSupport.execute(() -> orderService.listByPassenger(passengerId));
    }

    @Override
    public Result<List<OrderDTO>> listByDriver(String driverId) {
        return FacadeSupport.execute(() -> orderService.listByDriver(driverId));
    }

    @Override
    public Result<List<OrderDTO>> listByStatus(String status) {
        return FacadeSupport.execute(() -> orderService.listByStatus(status));
    }

    @Override
    public Result<PageDTO<OrderDTO>> query(OrderQuery query) {
        return FacadeSupport.execute(() -> orderService.query(query));
    }

    @Override
    public Result<OrderStatsDTO> stats() {
        return FacadeSupport.execute(orderService::stats);
    }
}