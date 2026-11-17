package com.qoder.ride.order.mapper;

import java.util.Map;

/** 订单记录动态查询：乘客/司机/状态/时间范围 + 分页。 */
public class OrderSqlProvider {

    public String query(Map<String, Object> params) {
        return "SELECT * FROM ride_order" + where(params)
                + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}";
    }

    public String count(Map<String, Object> params) {
        return "SELECT COUNT(*) FROM ride_order" + where(params);
    }

    private String where(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        if (params.get("passengerId") != null) {
            sb.append(" AND passenger_id=#{passengerId}");
        }
        if (params.get("driverId") != null) {
            sb.append(" AND driver_id=#{driverId}");
        }
        if (params.get("status") != null) {
            sb.append(" AND status=#{status}");
        }
        if (params.get("from") != null) {
            sb.append(" AND created_at>=#{from}");
        }
        if (params.get("to") != null) {
            sb.append(" AND created_at<=#{to}");
        }
        return sb.toString();
    }
}