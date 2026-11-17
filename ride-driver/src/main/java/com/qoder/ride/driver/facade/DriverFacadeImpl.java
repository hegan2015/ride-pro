package com.qoder.ride.driver.facade;

import com.qoder.ride.common.result.FacadeSupport;
import com.qoder.ride.common.result.Result;
import com.qoder.ride.driver.service.DriverService;
import com.qoder.ride.facade.driver.DriverCreateRequest;
import com.qoder.ride.facade.driver.DriverDTO;
import com.qoder.ride.facade.driver.DriverFacade;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class DriverFacadeImpl implements DriverFacade {

    private final DriverService driverService;

    @Override
    public Result<DriverDTO> create(DriverCreateRequest request) {
        return FacadeSupport.execute(() -> driverService.create(request));
    }

    @Override
    public Result<DriverDTO> get(String driverId) {
        return FacadeSupport.execute(() -> driverService.get(driverId));
    }

    @Override
    public Result<List<DriverDTO>> list() {
        return FacadeSupport.execute(driverService::list);
    }

    @Override
    public Result<DriverDTO> setOnline(String driverId, boolean online) {
        return FacadeSupport.execute(() -> driverService.setOnline(driverId, online));
    }

    @Override
    public Result<DriverDTO> setStatus(String driverId, String status) {
        return FacadeSupport.execute(() -> driverService.setStatus(driverId, status));
    }

    @Override
    public Result<Boolean> casStatus(String driverId, String from, String to) {
        return FacadeSupport.execute(() -> driverService.casStatus(driverId, from, to));
    }

    @Override
    public Result<Void> updateScore(String driverId, double score) {
        return FacadeSupport.execute(() -> {
            driverService.updateScore(driverId, score);
            return null;
        });
    }

    @Override
    public Result<Void> recordDecline(String driverId) {
        return FacadeSupport.execute(() -> {
            driverService.recordDecline(driverId);
            return null;
        });
    }
}