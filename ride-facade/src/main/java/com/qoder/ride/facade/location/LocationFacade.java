package com.qoder.ride.facade.location;

import com.qoder.ride.common.result.Result;

import java.util.List;

/** 位置域（LBS）：司机位置上报与就近查询。对标滴滴位置服务。 */
public interface LocationFacade {

    Result<Void> report(LocationReportRequest request);

    /** 就近在线司机（按距离升序）。 */
    Result<List<DriverLocationDTO>> nearby(NearbyRequest request);

    Result<DriverLocationDTO> get(String driverId);

    /** 行程轨迹（完单计价与行程回放）。 */
    Result<List<TrackPointDTO>> track(String orderId);
}