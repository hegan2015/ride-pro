package com.qoder.ride.facade.dispatch;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class DispatchResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String orderId;
    /** 本轮收到邀约的在线司机数。 */
    private Integer offeredDrivers;
    private Double radiusKm;
    /** 本轮实际邀约的司机 ID（订单域累计，指派模式顺延用）。 */
    private List<String> offeredDriverIds;
}