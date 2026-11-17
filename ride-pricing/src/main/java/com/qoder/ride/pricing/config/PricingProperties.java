package com.qoder.ride.pricing.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 计价规则配置。金额单位：分。 */
@Data
@ConfigurationProperties(prefix = "pricing")
public class PricingProperties {

    /** 分车型费率表（EXPRESS/PREMIER/TAXI）。 */
    private Map<String, ServiceRate> services = defaultServices();

    /** 高峰/夜间时段系数。 */
    private List<PeakWindow> peakWindows = defaultPeakWindows();

    /** 远途费起算里程（公里）。 */
    private double longDistanceThresholdKm = 15.0;
    /** 远途部分每公里加收（分）。 */
    private long longDistancePerKmFee = 100;

    @Data
    public static class ServiceRate {
        private String label;
        private long baseFee;
        private double baseKm;
        private long perKmFee;
        private long perMinFee;
        private long minFare;
    }

    @Data
    public static class PeakWindow {
        private String label;
        private int startHour;
        private int endHour;
        private double rate;
    }

    private static Map<String, ServiceRate> defaultServices() {
        Map<String, ServiceRate> map = new LinkedHashMap<>();
        map.put("EXPRESS", rate("快车", 1300, 3.0, 230, 50, 1300));
        map.put("PREMIER", rate("专车", 2000, 3.0, 320, 80, 2000));
        map.put("TAXI", rate("出租车", 1400, 3.0, 250, 60, 1400));
        return map;
    }

    private static ServiceRate rate(String label, long baseFee, double baseKm, long perKm, long perMin, long minFare) {
        ServiceRate r = new ServiceRate();
        r.label = label;
        r.baseFee = baseFee;
        r.baseKm = baseKm;
        r.perKmFee = perKm;
        r.perMinFee = perMin;
        r.minFare = minFare;
        return r;
    }

    private static List<PeakWindow> defaultPeakWindows() {
        List<PeakWindow> list = new ArrayList<>();
        list.add(window("早高峰", 7, 9, 1.3));
        list.add(window("晚高峰", 17, 19, 1.3));
        list.add(window("夜间", 22, 6, 1.2));
        return list;
    }

    private static PeakWindow window(String label, int start, int end, double rate) {
        PeakWindow w = new PeakWindow();
        w.label = label;
        w.startHour = start;
        w.endHour = end;
        w.rate = rate;
        return w;
    }
}