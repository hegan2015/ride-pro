package com.qoder.ride.facade.payment;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class WalletDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String userId;
    private Long balance;
    /** 提现冻结金额（分）。 */
    private Long frozenAmount;
    /** 可用余额 = balance - frozenAmount。 */
    private Long availableBalance;
    private Long updatedAt;
}