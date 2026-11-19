package com.qoder.ride.gateway.controller;

import com.qoder.ride.facade.order.CallRequest;
import com.qoder.ride.facade.order.OrderFacade;
import com.qoder.ride.facade.order.OrderQuery;
import com.qoder.ride.facade.order.RatingRequest;
import com.qoder.ride.gateway.web.GatewaySupport;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @DubboReference
    private OrderFacade orderFacade;

    @PostMapping
    public ResponseEntity<Object> call(@RequestBody CallRequest request) {
        return GatewaySupport.of(orderFacade.call(request));
    }

    @GetMapping
    public ResponseEntity<Object> list(@RequestParam(required = false) String passengerId,
                                       @RequestParam(required = false) String driverId,
                                       @RequestParam(required = false) String status) {
        if (passengerId != null) {
            return GatewaySupport.of(orderFacade.listByPassenger(passengerId));
        }
        if (driverId != null) {
            return GatewaySupport.of(orderFacade.listByDriver(driverId));
        }
        if (status != null) {
            return GatewaySupport.of(orderFacade.listByStatus(status));
        }
        return GatewaySupport.of(orderFacade.list());
    }

    @GetMapping("/query")
    public ResponseEntity<Object> query(@RequestParam(required = false) String passengerId,
                                        @RequestParam(required = false) String driverId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) Long from,
                                        @RequestParam(required = false) Long to,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        OrderQuery query = new OrderQuery();
        query.setPassengerId(passengerId);
        query.setDriverId(driverId);
        query.setStatus(status);
        query.setFrom(from);
        query.setTo(to);
        query.setPage(page);
        query.setSize(size);
        return GatewaySupport.of(orderFacade.query(query));
    }

    @GetMapping("/stats")
    public ResponseEntity<Object> stats() {
        return GatewaySupport.of(orderFacade.stats());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable String id) {
        return GatewaySupport.of(orderFacade.get(id));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<Object> accept(@PathVariable String id, @RequestParam String driverId) {
        return GatewaySupport.of(orderFacade.accept(id, driverId));
    }

    @PostMapping("/{id}/arrive")
    public ResponseEntity<Object> arrive(@PathVariable String id, @RequestParam String driverId) {
        return GatewaySupport.of(orderFacade.arrive(id, driverId));
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Object> start(@PathVariable String id, @RequestParam String driverId) {
        return GatewaySupport.of(orderFacade.startTrip(id, driverId));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Object> complete(@PathVariable String id, @RequestParam String driverId) {
        return GatewaySupport.of(orderFacade.completeTrip(id, driverId));
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<Object> pay(@PathVariable String id, @RequestParam(required = false) String channel) {
        return GatewaySupport.of(orderFacade.pay(id, channel));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Object> cancel(@PathVariable String id, @RequestParam(required = false) String reason) {
        return GatewaySupport.of(orderFacade.cancel(id, reason));
    }

    @PostMapping("/{id}/driver-cancel")
    public ResponseEntity<Object> driverCancel(@PathVariable String id, @RequestParam String driverId,
                                               @RequestParam(required = false) String reason) {
        return GatewaySupport.of(orderFacade.driverCancel(id, driverId, reason));
    }

    @PostMapping("/{id}/decline")
    public ResponseEntity<Object> decline(@PathVariable String id, @RequestParam String driverId) {
        return GatewaySupport.of(orderFacade.declineOffer(id, driverId));
    }

    @PostMapping("/{id}/rate")
    public ResponseEntity<Object> rate(@PathVariable String id, @RequestBody RatingRequest request) {
        request.setOrderId(id);
        return GatewaySupport.of(orderFacade.rate(request));
    }

    @GetMapping("/{id}/ratings")
    public ResponseEntity<Object> ratings(@PathVariable String id) {
        return GatewaySupport.of(orderFacade.ratings(id));
    }

    @GetMapping("/driver-ratings/{driverId}")
    public ResponseEntity<Object> driverRatings(@PathVariable String driverId) {
        return GatewaySupport.of(orderFacade.driverRatings(driverId));
    }

    @PostMapping("/{id}/modify-dest")
    public ResponseEntity<Object> modifyDest(@PathVariable String id, @RequestParam String destName,
                                             @RequestParam Double destLat, @RequestParam Double destLng) {
        return GatewaySupport.of(orderFacade.modifyDest(id, destName, destLat, destLng));
    }
}