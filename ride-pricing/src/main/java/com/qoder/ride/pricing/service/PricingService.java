package com.qoder.ride.pricing.service;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;
import com.qoder.ride.common.util.GeoUtil;
import com.qoder.ride.facade.pricing.FareDTO;
import com.qoder.ride.facade.pricing.FareRequest;
import com.qoder.ride.facade.pricing.PricingRulesDTO;
import com.qoder.ride.pricing.config.PricingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计价引擎：分车型费率 + 高峰/夜间系数 + 远途费 + 最低消费。
 * 规则版本随配置内容变化，下单快照落订单，完单沿用同版本语义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingService {

    private final PricingProperties properties;

    @org.apache.dubbo.config.annotation.DubboReference(check = false)
    private com.qoder.ride.facade.support.SupportFacade supportFacade;

    /** 运营配置覆盖（ride-support）；不可达/未配置时回落本地费率，保证计价不中断。 */
    private String cfg(String key) {
        try {
            if (supportFacade == null) return null;
            String v = supportFacade.allConfig().orThrow().get(key);
            return v == null || v.isBlank() ? null : v.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private PricingProperties.ServiceRate overrideRate(String serviceType, PricingProperties.ServiceRate base) {
        String p = "pricing." + serviceType + ".";
        String baseFee = cfg(p + "baseFee"), baseKm = cfg(p + "baseKm"), perKm = cfg(p + "perKmFee"), perMin = cfg(p + "perMinFee"), minFare = cfg(p + "minFare");
        if (baseFee == null && baseKm == null && perKm == null && perMin == null && minFare == null) return base;
        PricingProperties.ServiceRate r = new PricingProperties.ServiceRate();
        r.setLabel(base.getLabel());
        r.setBaseFee(baseFee != null ? Long.parseLong(baseFee) : base.getBaseFee());
        r.setBaseKm(baseKm != null ? Double.parseDouble(baseKm) : base.getBaseKm());
        r.setPerKmFee(perKm != null ? Long.parseLong(perKm) : base.getPerKmFee());
        r.setPerMinFee(perMin != null ? Long.parseLong(perMin) : base.getPerMinFee());
        r.setMinFare(minFare != null ? Long.parseLong(minFare) : base.getMinFare());
        return r;
    }

    private boolean surgeEnabled() {
        String v = cfg("pricing.surgeEnabled");
        return v == null || "true".equalsIgnoreCase(v);
    }

    public FareDTO quote(FareRequest request) {
        String serviceType = normalizeService(request.getServiceType());
        PricingProperties.ServiceRate rate = overrideRate(serviceType, properties.getServices().get(serviceType));

        double distanceKm;
        long durationMin;
        if (request.getDistanceKmOverride() != null && request.getDistanceKmOverride() >= 0) {
            distanceKm = request.getDistanceKmOverride();
            durationMin = request.getDurationMinOverride() != null && request.getDurationMinOverride() > 0
                    ? request.getDurationMinOverride() : GeoUtil.estimateMinutes(distanceKm);
        } else {
            requireCoord(request.getOriginLat(), request.getOriginLng());
            requireCoord(request.getDestLat(), request.getDestLng());
            distanceKm = GeoUtil.distanceKm(request.getOriginLat(), request.getOriginLng(),
                    request.getDestLat(), request.getDestLng());
            durationMin = GeoUtil.estimateMinutes(distanceKm);
        }

        double peakRate = surgeEnabled() ? currentPeakRate() : 1.0;
        double billableKm = Math.max(0, distanceKm - rate.getBaseKm());
        long distanceFee = Math.round(billableKm * rate.getPerKmFee() * peakRate);
        long timeFee = Math.round(durationMin * rate.getPerMinFee() * peakRate);
        double longKm = Math.max(0, distanceKm - properties.getLongDistanceThresholdKm());
        long longDistanceFee = Math.round(longKm * properties.getLongDistancePerKmFee());
        long totalFee = Math.max(rate.getMinFare(), rate.getBaseFee() + distanceFee + timeFee + longDistanceFee);

        FareDTO fare = new FareDTO();
        fare.setDistanceKm(round2(distanceKm));
        fare.setDurationMin(durationMin);
        fare.setBaseFee(rate.getBaseFee());
        fare.setDistanceFee(distanceFee);
        fare.setTimeFee(timeFee);
        fare.setLongDistanceFee(longDistanceFee);
        fare.setPeakRate(peakRate);
        fare.setVersion(version());
        fare.setServiceType(serviceType);
        fare.setTotalFee(totalFee);
        log.info("计价 service={} distanceKm={} durationMin={} peakRate={} totalFee={}",
                serviceType, fare.getDistanceKm(), durationMin, peakRate, totalFee);
        return fare;
    }

    public PricingRulesDTO rules() {
        PricingRulesDTO dto = new PricingRulesDTO();
        dto.setVersion(version());
        Map<String, PricingRulesDTO.ServiceRate> services = new LinkedHashMap<>();
        properties.getServices().forEach((key, r) -> {
            PricingRulesDTO.ServiceRate s = new PricingRulesDTO.ServiceRate();
            s.setLabel(r.getLabel());
            s.setBaseFee(r.getBaseFee());
            s.setBaseKm(r.getBaseKm());
            s.setPerKmFee(r.getPerKmFee());
            s.setPerMinFee(r.getPerMinFee());
            s.setMinFare(r.getMinFare());
            services.put(key, s);
        });
        dto.setServices(services);
        List<PricingRulesDTO.PeakWindow> windows = new ArrayList<>();
        for (PricingProperties.PeakWindow w : properties.getPeakWindows()) {
            PricingRulesDTO.PeakWindow p = new PricingRulesDTO.PeakWindow();
            p.setLabel(w.getLabel());
            p.setStartHour(w.getStartHour());
            p.setEndHour(w.getEndHour());
            p.setRate(w.getRate());
            windows.add(p);
        }
        dto.setPeakWindows(windows);
        dto.setLongDistanceThresholdKm(properties.getLongDistanceThresholdKm());
        dto.setLongDistancePerKmFee(properties.getLongDistancePerKmFee());
        return dto;
    }

    public String normalizeService(String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return "EXPRESS";
        }
        String upper = serviceType.toUpperCase();
        if (!properties.getServices().containsKey(upper)) {
            throw new BizException(ResultCode.PARAM_ERROR, "未知服务类型: " + serviceType);
        }
        return upper;
    }

    private double currentPeakRate() {
        int hour = LocalTime.now().getHour();
        double rate = 1.0;
        for (PricingProperties.PeakWindow w : properties.getPeakWindows()) {
            boolean in = w.getStartHour() <= w.getEndHour()
                    ? hour >= w.getStartHour() && hour < w.getEndHour()
                    : hour >= w.getStartHour() || hour < w.getEndHour();
            if (in) {
                rate = Math.max(rate, w.getRate());
            }
        }
        return rate;
    }

    /** 规则版本 = 配置内容指纹，配置变更即新版本（订单快照据此追溯）。 */
    private String version() {
        StringBuilder sb = new StringBuilder();
        properties.getServices().forEach((k, r) -> sb.append(k).append(':').append(r.getBaseFee()).append(',')
                .append(r.getBaseKm()).append(',').append(r.getPerKmFee()).append(',')
                .append(r.getPerMinFee()).append(',').append(r.getMinFare()).append(';'));
        for (PricingProperties.PeakWindow w : properties.getPeakWindows()) {
            sb.append(w.getStartHour()).append('-').append(w.getEndHour()).append(':').append(w.getRate()).append(';');
        }
        sb.append(properties.getLongDistanceThresholdKm()).append(':').append(properties.getLongDistancePerKmFee());
        return "v1-" + Integer.toHexString(sb.toString().hashCode());
    }

    private void requireCoord(Double lat, Double lng) {
        if (lat == null || lng == null || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new BizException(ResultCode.PARAM_ERROR, "经纬度非法");
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}