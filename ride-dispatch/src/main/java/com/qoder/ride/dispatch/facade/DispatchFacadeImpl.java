package com.qoder.ride.dispatch.facade;

import com.qoder.ride.common.result.FacadeSupport;
import com.qoder.ride.common.result.Result;
import com.qoder.ride.dispatch.service.DispatchService;
import com.qoder.ride.facade.dispatch.DispatchFacade;
import com.qoder.ride.facade.dispatch.DispatchRequest;
import com.qoder.ride.facade.dispatch.DispatchResultDTO;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
@RequiredArgsConstructor
public class DispatchFacadeImpl implements DispatchFacade {

    private final DispatchService dispatchService;

    @Override
    public Result<DispatchResultDTO> dispatch(DispatchRequest request) {
        return FacadeSupport.execute(() -> dispatchService.dispatch(request));
    }
}