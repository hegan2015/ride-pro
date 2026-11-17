package com.qoder.ride.facade.order;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/** 订单运营统计（管理端仪表盘）。金额单位：分。 */
@Data
public class OrderStatsDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private long totalOrders;
    private Map<String, Long> statusCounts;
    private long todayOrders;
    private long todayPaid;
    /** 今日实付总额（PAID 且 paid_at 在今日）。 */
    private long todayGmv;
    /** 累计实付总额。 */
    private long totalGmv;
    /** 平均接单时长（毫秒，created_at→accepted_at）。 */
    private Long avgAcceptMs;
    /** 接单率：曾被接单（含后续取消/退款）/ 总单。 */
    private Double acceptRate;
}