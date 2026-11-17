package com.qoder.ride.location.facade;

import com.qoder.ride.common.result.FacadeSupport;
import com.qoder.ride.common.result.Result;
import com.qoder.ride.facade.location.DriverLocationDTO;
import com.qoder.ride.facade.location.LocationFacade;
import com.qoder.ride.facade.location.LocationReportRequest;
import com.qoder.ride.facade.location.NearbyRequest;
import com.qoder.ride.facade.location.TrackPointDTO;
import com.qoder.ride.location.service.LocationService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class LocationFacadeImpl implements LocationFacade {

    private final LocationService locationService;

    @Override
    public Result<Void> report(LocationReportRequest request) {
        return FacadeSupport.execute(() -> {
            locationService.report(request);
            return null;
        });
    }

    @Override
    public Result<List<DriverLocationDTO>> nearby(NearbyRequest request) {
        return FacadeSupport.execute(() -> locationService.nearby(request));
    }

    @Override
    public Result<DriverLocationDTO> get(String driverId) {
        return FacadeSupport.execute(() -> locationService.get(driverId));
    }

    @Override
    public Result<List<TrackPointDTO>> track(String orderId) {
        return FacadeSupport.execute(() -> locationService.track(orderId));
    }
}