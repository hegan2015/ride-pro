package com.qoder.ride.dispatch.service;

import com.qoder.ride.facade.dispatch.DispatchRequest;
import com.qoder.ride.facade.dispatch.DispatchResultDTO;
import com.qoder.ride.facade.driver.DriverDTO;
import com.qoder.ride.facade.driver.DriverFacade;
import com.qoder.ride.facade.location.DriverLocationDTO;
import com.qoder.ride.facade.location.LocationFacade;
import com.qoder.ride.facade.location.NearbyRequest;
import com.qoder.ride.facade.push.PushFacade;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 派单引擎：就近候选 + 评分排序（距离 60% + 服务分 30% + 拒单率 10%）。
 * grab=广播抢单（订单 CAS 保证仅一人成功）；assign=逐个指派（Top1 邀约，超时由订单域顺延）。
 */
@Slf4j
@Service
public class DispatchService {

    @DubboReference
    private LocationFacade locationFacade;
    @DubboReference
    private PushFacade pushFacade;
    @DubboReference
    private DriverFacade driverFacade;

    @Value("${dispatch.offer-limit:20}")
    private int offerLimit;

    public DispatchResultDTO dispatch(DispatchRequest request) {
        NearbyRequest nearby = new NearbyRequest();
        nearby.setLat(request.getOriginLat());
        nearby.setLng(request.getOriginLng());
        nearby.setRadiusKm(request.getRadiusKm());
        nearby.setLimit(Math.max(offerLimit, 50));
        List<DriverLocationDTO> candidates = locationFacade.nearby(nearby).orThrow();

        Map<String, DriverDTO> drivers = driverFacade.list().orThrow().stream()
                .collect(Collectors.toMap(DriverDTO::getId, Function.identity()));
        Set<String> excluded = splitIds(request.getExcludeDriverIds());
        String serviceType = request.getServiceType() == null ? "EXPRESS" : request.getServiceType();
        double radius = request.getRadiusKm() == null || request.getRadiusKm() <= 0 ? 5.0 : request.getRadiusKm();

        List<DriverLocationDTO> eligible = candidates.stream()
                .filter(loc -> !excluded.contains(loc.getDriverId()))
                .filter(loc -> {
                    DriverDTO d = drivers.get(loc.getDriverId());
                    return d != null && serviceType.equals(d.getServiceType() == null ? "EXPRESS" : d.getServiceType());
                })
                .sorted((a, b) -> Double.compare(
                        score(b, drivers.get(b.getDriverId()), radius),
                        score(a, drivers.get(a.getDriverId()), radius)))
                .toList();

        boolean assign = "assign".equalsIgnoreCase(request.getMode());
        List<DriverLocationDTO> targets = assign
                ? eligible.stream().limit(1).toList()
                : eligible.stream().limit(offerLimit).toList();

        List<String> offeredIds = new ArrayList<>();
        for (DriverLocationDTO driver : targets) {
            DriverDTO info = drivers.get(driver.getDriverId());
            Map<String, Object> data = new HashMap<>();
            data.put("orderId", request.getOrderId());
            data.put("passengerName", request.getPassengerName());
            data.put("originName", request.getOriginName());
            data.put("originLat", request.getOriginLat());
            data.put("originLng", request.getOriginLng());
            data.put("destName", request.getDestName());
            data.put("destLat", request.getDestLat());
            data.put("destLng", request.getDestLng());
            data.put("estimateFee", request.getEstimateFee());
            data.put("distanceKm", request.getDistanceKm());
            data.put("distanceToPassenger", driver.getDistanceKm());
            data.put("expireAt", request.getOfferExpireAt());
            data.put("mode", assign ? "assign" : "grab");
            data.put("serviceType", serviceType);
            data.put("serviceScore", info == null ? 5.0 : info.getServiceScore());
            try {
                pushFacade.pushToDriver(driver.getDriverId(), "ORDER_OFFER", data);
                offeredIds.add(driver.getDriverId());
            } catch (Exception e) {
                log.warn("邀约推送失败 driverId={}: {}", driver.getDriverId(), e.getMessage());
            }
        }
        log.info("派单 orderId={} mode={} radius={}km 候选={} 邀约={}",
                request.getOrderId(), assign ? "assign" : "grab", request.getRadiusKm(), eligible.size(), offeredIds.size());

        DispatchResultDTO result = new DispatchResultDTO();
        result.setOrderId(request.getOrderId());
        result.setOfferedDrivers(offeredIds.size());
        result.setRadiusKm(request.getRadiusKm());
        result.setOfferedDriverIds(offeredIds);
        return result;
    }

    private double score(DriverLocationDTO loc, DriverDTO driver, double radius) {
        double dist = loc.getDistanceKm() == null ? radius : loc.getDistanceKm();
        double distancePart = Math.max(0, 1 - dist / radius);
        double scorePart = (driver == null || driver.getServiceScore() == null ? 5.0 : driver.getServiceScore()) / 5.0;
        int declines = driver == null || driver.getDeclineCount() == null ? 0 : driver.getDeclineCount();
        double declinePart = 1.0 / (1 + declines);
        return 0.6 * distancePart + 0.3 * scorePart + 0.1 * declinePart;
    }

    private Set<String> splitIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return new HashSet<>();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }
}