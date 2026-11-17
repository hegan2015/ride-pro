package com.qoder.ride.gateway.controller;

import com.qoder.ride.facade.location.LocationFacade;
import com.qoder.ride.facade.location.LocationReportRequest;
import com.qoder.ride.facade.location.NearbyRequest;
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
@RequestMapping("/api/locations")
public class LocationController {

    @DubboReference
    private LocationFacade locationFacade;

    @PostMapping
    public ResponseEntity<Object> report(@RequestBody LocationReportRequest request) {
        return GatewaySupport.of(locationFacade.report(request));
    }

    @GetMapping("/nearby")
    public ResponseEntity<Object> nearby(@RequestParam Double lat, @RequestParam Double lng,
                                         @RequestParam(required = false) Double radiusKm,
                                         @RequestParam(required = false) Integer limit) {
        NearbyRequest request = new NearbyRequest();
        request.setLat(lat);
        request.setLng(lng);
        request.setRadiusKm(radiusKm);
        request.setLimit(limit);
        return GatewaySupport.of(locationFacade.nearby(request));
    }

    @GetMapping("/track/{orderId}")
    public ResponseEntity<Object> track(@PathVariable String orderId) {
        return GatewaySupport.of(locationFacade.track(orderId));
    }

    @GetMapping("/{driverId}")
    public ResponseEntity<Object> get(@PathVariable String driverId) {
        return GatewaySupport.of(locationFacade.get(driverId));
    }
}