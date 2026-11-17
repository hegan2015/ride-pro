package com.qoder.ride.gateway.controller;

import com.qoder.ride.facade.pricing.FareRequest;
import com.qoder.ride.facade.pricing.PricingFacade;
import com.qoder.ride.gateway.web.GatewaySupport;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pricing")
public class PricingController {

    @DubboReference
    private PricingFacade pricingFacade;

    @PostMapping("/quote")
    public ResponseEntity<Object> quote(@RequestBody FareRequest request) {
        return GatewaySupport.of(pricingFacade.quote(request));
    }

    @GetMapping("/rules")
    public ResponseEntity<Object> rules() {
        return GatewaySupport.of(pricingFacade.rules());
    }
}