package com.qoder.ride.location.service;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;
import com.qoder.ride.common.util.GeoUtil;
import com.qoder.ride.facade.driver.DriverDTO;
import com.qoder.ride.facade.driver.DriverFacade;
import com.qoder.ride.facade.location.DriverLocationDTO;
import com.qoder.ride.facade.location.LocationReportRequest;
import com.qoder.ride.facade.location.NearbyRequest;
import com.qoder.ride.facade.location.TrackPointDTO;
import com.qoder.ride.facade.push.PushFacade;
import com.qoder.ride.location.dataobject.DriverLocationDO;
import com.qoder.ride.location.dataobject.TrackPointDO;
import com.qoder.ride.location.mapper.DriverLocationMapper;
import com.qoder.ride.location.mapper.TrackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocationService {

    private final DriverLocationMapper locationMapper;
    private final TrackMapper trackMapper;

    @DubboReference
    private DriverFacade driverFacade;
    @DubboReference
    private PushFacade pushFacade;

    public void report(LocationReportRequest request) {
        if (request.getDriverId() == null || request.getDriverId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "司机 ID 必填");
        }
        requireCoord(request.getLat(), request.getLng());
        long now = System.currentTimeMillis();
        DriverLocationDO location = new DriverLocationDO();
        location.setDriverId(request.getDriverId());
        location.setLat(request.getLat());
        location.setLng(request.getLng());
        location.setUpdatedAt(now);
        locationMapper.upsert(location);

        // 行程中上报：轨迹逐点落库 + 实时位置转发乘客（推送尽力而为，失败不影响上报）。
        if (request.getOrderId() != null && !request.getOrderId().isBlank()) {
            TrackPointDO point = new TrackPointDO();
            point.setOrderId(request.getOrderId());
            point.setSeq(trackMapper.nextSeq(request.getOrderId()));
            point.setLat(request.getLat());
            point.setLng(request.getLng());
            point.setTs(now);
            trackMapper.insert(point);
            if (request.getPassengerId() != null && !request.getPassengerId().isBlank()) {
                try {
                    Map<String, Object> data = new HashMap<>();
                    data.put("orderId", request.getOrderId());
                    data.put("driverId", request.getDriverId());
                    data.put("lat", request.getLat());
                    data.put("lng", request.getLng());
                    data.put("ts", now);
                    pushFacade.pushToPassenger(request.getPassengerId(), "DRIVER_LOCATION", data);
                } catch (Exception e) {
                    log.warn("实时位置转发失败 orderId={}: {}", request.getOrderId(), e.getMessage());
                }
            }
        }
        log.debug("位置上报 driverId={} lat={} lng={}", request.getDriverId(), request.getLat(), request.getLng());
    }

    public List<DriverLocationDTO> nearby(NearbyRequest request) {
        requireCoord(request.getLat(), request.getLng());
        double radius = request.getRadiusKm() == null ? 5.0 : request.getRadiusKm();
        int limit = request.getLimit() == null ? 10 : request.getLimit();
        Set<String> onlineDrivers = onlineDriverIds();
        double dLat = radius / 111.0;
        double dLng = radius / (111.0 * Math.max(0.01, Math.cos(Math.toRadians(request.getLat()))));
        return locationMapper.selectInBox(request.getLat() - dLat, request.getLat() + dLat,
                        request.getLng() - dLng, request.getLng() + dLng).stream()
                .filter(loc -> onlineDrivers.contains(loc.getDriverId()))
                .map(loc -> toDTO(loc, GeoUtil.distanceKm(request.getLat(), request.getLng(), loc.getLat(), loc.getLng())))
                .filter(dto -> dto.getDistanceKm() <= radius)
                .sorted(Comparator.comparingDouble(DriverLocationDTO::getDistanceKm))
                .limit(limit)
                .toList();
    }

    public DriverLocationDTO get(String driverId) {
        DriverLocationDO location = locationMapper.selectById(driverId);
        if (location == null) {
            throw new BizException(ResultCode.NOT_FOUND, "司机位置不存在: " + driverId);
        }
        return toDTO(location, null);
    }

    public List<TrackPointDTO> track(String orderId) {
        return trackMapper.selectByOrder(orderId).stream().map(p -> {
            TrackPointDTO dto = new TrackPointDTO();
            dto.setOrderId(p.getOrderId());
            dto.setSeq(p.getSeq());
            dto.setLat(p.getLat());
            dto.setLng(p.getLng());
            dto.setTs(p.getTs());
            return dto;
        }).toList();
    }

    private Set<String> onlineDriverIds() {
        return driverFacade.list().orThrow().stream()
                .filter(d -> "ONLINE".equals(d.getStatus()))
                .map(DriverDTO::getId)
                .collect(Collectors.toSet());
    }

    private void requireCoord(Double lat, Double lng) {
        if (lat == null || lng == null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new BizException(ResultCode.PARAM_ERROR, "经纬度非法");
        }
    }

    private DriverLocationDTO toDTO(DriverLocationDO location, Double distanceKm) {
        DriverLocationDTO dto = new DriverLocationDTO();
        dto.setDriverId(location.getDriverId());
        dto.setLat(location.getLat());
        dto.setLng(location.getLng());
        dto.setDistanceKm(distanceKm);
        dto.setUpdatedAt(location.getUpdatedAt());
        return dto;
    }
}