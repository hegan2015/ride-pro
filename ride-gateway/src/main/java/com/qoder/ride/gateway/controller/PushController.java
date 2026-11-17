package com.qoder.ride.gateway.controller;

import com.qoder.ride.facade.push.PushFacade;
import com.qoder.ride.gateway.web.GatewaySupport;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/push")
public class PushController {

    @DubboReference
    private PushFacade pushFacade;

    @GetMapping("/online")
    public ResponseEntity<Object> online() {
        return GatewaySupport.of(pushFacade.onlineCount());
    }
}