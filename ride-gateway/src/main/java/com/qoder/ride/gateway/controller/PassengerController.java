package com.qoder.ride.gateway.controller;

import com.qoder.ride.facade.passenger.PassengerCreateRequest;
import com.qoder.ride.facade.passenger.PassengerFacade;
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
@RequestMapping("/api/passengers")
public class PassengerController {

    @DubboReference
    private PassengerFacade passengerFacade;

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody PassengerCreateRequest request) {
        return GatewaySupport.of(passengerFacade.create(request));
    }

    @GetMapping
    public ResponseEntity<Object> list() {
        return GatewaySupport.of(passengerFacade.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable String id) {
        return GatewaySupport.of(passengerFacade.get(id));
    }

    /** 免密自动扣款授权开关。 */
    @PostMapping("/{id}/auto-pay")
    public ResponseEntity<Object> autoPay(@PathVariable String id, @RequestParam boolean enabled) {
        return GatewaySupport.of(passengerFacade.setAutoPay(id, enabled));
    }
}