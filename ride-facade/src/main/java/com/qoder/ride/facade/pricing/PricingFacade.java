package com.qoder.ride.facade.pricing;

import com.qoder.ride.common.result.Result;

/** 计价域：预估价与最终价。对标滴滴计价引擎。 */
public interface PricingFacade {

    /** 预估/最终计价（直线距离近似，可传轨迹里程/时长覆盖）。金额单位：分。 */
    Result<FareDTO> quote(FareRequest request);

    /** 当前计价规则表（分车型费率 + 高峰时段 + 远途费）。 */
    Result<PricingRulesDTO> rules();
}