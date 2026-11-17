package com.qoder.ride.common.util;

/**
 * 地理工具。Haversine 公式计算球面距离，供计价与就近派单使用。
 * 演示系统用直线距离近似路网距离（生产应接入路径规划服务）。
 */
public final class GeoUtil {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoUtil() {
    }

    /** 两点球面距离（公里）。 */
    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 估算行驶时长（分钟）：按城市平均车速 30km/h。 */
    public static long estimateMinutes(double distanceKm) {
        return Math.max(1, Math.round(distanceKm / 30.0 * 60));
    }
}