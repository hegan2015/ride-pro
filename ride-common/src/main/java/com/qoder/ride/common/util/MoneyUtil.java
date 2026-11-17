package com.qoder.ride.common.util;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;

/**
 * 金额工具。全链路以「分」为单位的 long 运算，杜绝浮点误差。
 */
public final class MoneyUtil {

    public static final long MAX_AMOUNT = 1_000_000_000_000L;

    private MoneyUtil() {
    }

    /** 平台服务费（分）= 金额 × 基点 / 10000，四舍五入。 */
    public static long fee(long amount, int feeBps) {
        return (amount * feeBps + 5000) / 10000;
    }

    public static long requireValid(long amount) {
        if (amount <= 0 || amount > MAX_AMOUNT) {
            throw new BizException(ResultCode.PARAM_ERROR, "金额无效：需为 1 ~ " + MAX_AMOUNT + " 的整数（分）");
        }
        return amount;
    }
}