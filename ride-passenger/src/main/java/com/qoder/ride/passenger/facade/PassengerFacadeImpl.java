package com.qoder.ride.passenger.facade;

import com.qoder.ride.common.result.FacadeSupport;
import com.qoder.ride.common.result.Result;
import com.qoder.ride.facade.passenger.PassengerCreateRequest;
import com.qoder.ride.facade.passenger.PassengerDTO;
import com.qoder.ride.facade.passenger.PassengerFacade;
import com.qoder.ride.passenger.service.PassengerService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class PassengerFacadeImpl implements PassengerFacade {

    private final PassengerService passengerService;

    @Override
    public Result<PassengerDTO> create(PassengerCreateRequest request) {
        return FacadeSupport.execute(() -> passengerService.create(request));
    }

    @Override
    public Result<PassengerDTO> get(String passengerId) {
        return FacadeSupport.execute(() -> passengerService.get(passengerId));
    }

    @Override
    public Result<PassengerDTO> setAutoPay(String passengerId, boolean enabled) {
        return FacadeSupport.execute(() -> passengerService.setAutoPay(passengerId, enabled));
    }

    @Override
    public Result<PassengerDTO> setBlocked(String passengerId, boolean blocked) {
        return FacadeSupport.execute(() -> passengerService.setBlocked(passengerId, blocked));
    }

    @Override
    public Result<List<PassengerDTO>> list() {
        return FacadeSupport.execute(passengerService::list);
    }
}