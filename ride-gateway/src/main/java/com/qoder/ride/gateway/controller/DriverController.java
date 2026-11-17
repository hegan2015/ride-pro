package com.qoder.ride.gateway.controller;

import com.qoder.ride.facade.driver.DriverCreateRequest;
import com.qoder.ride.facade.driver.DriverFacade;
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
@RequestMapping("/api/drivers")
public class DriverController {

    @DubboReference
    private DriverFacade driverFacade;

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody DriverCreateRequest request) {
        return GatewaySupport.of(driverFacade.create(request));
    }

    @GetMapping
    public ResponseEntity<Object> list() {
        return GatewaySupport.of(driverFacade.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable String id) {
        return GatewaySupport.of(driverFacade.get(id));
    }

    @PostMapping("/{id}/online")
    public ResponseEntity<Object> setOnline(@PathVariable String id, @RequestParam boolean online) {
        return GatewaySupport.of(driverFacade.setOnline(id, online));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<Object> setStatus(@PathVariable String id, @RequestParam String status) {
        return GatewaySupport.of(driverFacade.setStatus(id, status));
    }
}