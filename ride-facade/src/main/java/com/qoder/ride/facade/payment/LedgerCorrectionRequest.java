package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 双账本历史冲正：本地多腿更正（借贷必相等 Σ=0），只增冲正腿不改原行，不镜像 payment-pro
 * （对端账务本就正确或已单独冲正）。requestId 幂等，重复提交返回同一结果不重复动账。
 */
@Data
public class LedgerCorrectionRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String requestId;
    /** 冲正事由（必填，落审计与流水备注口径）。 */
    private String reason;
    /** 关联订单号（可空，用于按单追溯）。 */
    private String orderId;
    private List<Leg> legs;

    @Data
    public static class Leg implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String userId;
        /** 流水类型，如 CARD_FUNDING_FIX / TERMINAL_FAILED_FIX。 */
        private String bizType;
        /** 正=贷记入账，负=借记出资；外部清算户 SYS_CARD 允许透支。 */
        private Long amount;
    }
}