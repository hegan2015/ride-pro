package com.qoder.ride.facade;

import com.qoder.ride.common.result.Result;

/** 服务健康探针，供网关聚合与启停脚本就绪检测。 */
public interface HealthFacade {

    Result<String> ping();
}