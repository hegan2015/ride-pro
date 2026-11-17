package com.qoder.ride.payment.mapper;

import java.util.Map;

/** 流水动态查询：用户/订单/类型/时间范围 + 分页。 */
public class LedgerSqlProvider {

    public String query(Map<String, Object> params) {
        return "SELECT * FROM ledger_entry" + where(params)
                + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}";
    }

    public String count(Map<String, Object> params) {
        return "SELECT COUNT(*) FROM ledger_entry" + where(params);
    }

    private String where(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        if (params.get("userId") != null) {
            sb.append(" AND user_id=#{userId}");
        }
        if (params.get("orderId") != null) {
            sb.append(" AND order_id=#{orderId}");
        }
        if (params.get("bizType") != null) {
            sb.append(" AND biz_type=#{bizType}");
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