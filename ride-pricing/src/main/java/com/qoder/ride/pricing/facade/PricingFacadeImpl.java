package com.qoder.ride.pricing.facade;

import com.qoder.ride.common.result.FacadeSupport;
import com.qoder.ride.common.result.Result;
import com.qoder.ride.facade.pricing.FareDTO;
import com.qoder.ride.facade.pricing.FareRequest;
import com.qoder.ride.facade.pricing.PricingFacade;
import com.qoder.ride.facade.pricing.PricingRulesDTO;
import com.qoder.ride.pricing.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
@RequiredArgsConstructor
public class PricingFacadeImpl implements PricingFacade {

    private final PricingService pricingService;

    @Override
    public Result<FareDTO> quote(FareRequest request) {
        return FacadeSupport.execute(() -> pricingService.quote(request));
    }

    @Override
    public Result<PricingRulesDTO> rules() {
        return FacadeSupport.execute(pricingService::rules);
    }
}