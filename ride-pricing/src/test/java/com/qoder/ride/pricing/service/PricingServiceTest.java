package com.qoder.ride.pricing.service;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.facade.pricing.FareDTO;
import com.qoder.ride.facade.pricing.FareRequest;
import com.qoder.ride.pricing.config.PricingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingServiceTest {

    private PricingProperties properties;
    private PricingService service;

    @BeforeEach
    void setUp() {
        properties = new PricingProperties();
        properties.setPeakWindows(new ArrayList<>());
        service = new PricingService(properties);
    }

    private FareRequest override(String svc, double km, long min) {
        FareRequest r = new FareRequest();
        r.setServiceType(svc);
        r.setDistanceKmOverride(km);
        r.setDurationMinOverride(min);
        return r;
    }

    @Test
    void expressBaseQuote() {
        FareDTO f = service.quote(override("EXPRESS", 10, 20));
        assertEquals(1300, f.getBaseFee());
        assertEquals(7 * 230, f.getDistanceFee());
        assertEquals(20 * 50, f.getTimeFee());
        assertEquals(0, f.getLongDistanceFee());
        assertEquals(1300 + 1610 + 1000, f.getTotalFee());
        assertEquals("EXPRESS", f.getServiceType());
        assertEquals(1.0, f.getPeakRate());
    }

    @Test
    void defaultServiceIsExpress() {
        FareDTO f = service.quote(override(null, 10, 20));
        assertEquals("EXPRESS", f.getServiceType());
        assertEquals(3910, f.getTotalFee());
    }

    @Test
    void unknownServiceRejected() {
        assertThrows(BizException.class, () -> service.quote(override("HELICOPTER", 10, 20)));
    }

    @Test
    void premierAndTaxiRatesDiffer() {
        FareDTO premier = service.quote(override("PREMIER", 10, 20));
        assertEquals(2000 + 7 * 320 + 20 * 80, premier.getTotalFee());
        FareDTO taxi = service.quote(override("TAXI", 10, 20));
        assertEquals(1400 + 7 * 250 + 20 * 60, taxi.getTotalFee());
    }

    @Test
    void longDistanceFeeBeyondThreshold() {
        FareDTO f = service.quote(override("EXPRESS", 20, 40));
        assertEquals(5 * 100, f.getLongDistanceFee());
        assertEquals(1300 + 17 * 230 + 40 * 50 + 500, f.getTotalFee());
    }

    @Test
    void minFareFloor() {
        FareDTO f = service.quote(override("EXPRESS", 0.2, 1));
        assertTrue(f.getTotalFee() >= 1300);
    }

    @Test
    void peakWindowMultipliesDistanceAndTimeFees() {
        PricingProperties.PeakWindow allDay = new PricingProperties.PeakWindow();
        allDay.setLabel("全天高峰");
        allDay.setStartHour(0);
        allDay.setEndHour(24);
        allDay.setRate(1.3);
        properties.setPeakWindows(List.of(allDay));
        FareDTO f = service.quote(override("EXPRESS", 10, 20));
        assertEquals(1.3, f.getPeakRate());
        assertEquals(Math.round(7 * 230 * 1.3), f.getDistanceFee());
        assertEquals(Math.round(20 * 50 * 1.3), f.getTimeFee());
        assertEquals(1300 + f.getDistanceFee() + f.getTimeFee(), f.getTotalFee());
    }

    @Test
    void overnightWindowWraps() {
        PricingProperties.PeakWindow night = new PricingProperties.PeakWindow();
        night.setLabel("夜间");
        night.setStartHour(22);
        night.setEndHour(6);
        night.setRate(1.2);
        properties.setPeakWindows(List.of(night));
        FareDTO f = service.quote(override("EXPRESS", 10, 20));
        int hour = java.time.LocalTime.now().getHour();
        double expected = (hour >= 22 || hour < 6) ? 1.2 : 1.0;
        assertEquals(expected, f.getPeakRate());
    }

    @Test
    void versionIsConfigFingerprint() {
        String v1 = service.quote(override("EXPRESS", 1, 1)).getVersion();
        String v2 = service.quote(override("EXPRESS", 5, 10)).getVersion();
        assertEquals(v1, v2);
        properties.getServices().get("EXPRESS").setPerKmFee(240);
        String v3 = service.quote(override("EXPRESS", 1, 1)).getVersion();
        assertNotEquals(v1, v3);
    }

    @Test
    void rulesExposeAllServices() {
        assertEquals(3, service.rules().getServices().size());
        assertTrue(service.rules().getServices().containsKey("PREMIER"));
        assertEquals(15.0, service.rules().getLongDistanceThresholdKm());
    }

    @Test
    void invalidCoordRejectedWithoutOverride() {
        FareRequest r = new FareRequest();
        r.setOriginLat(999.0);
        r.setOriginLng(116.0);
        r.setDestLat(39.9);
        r.setDestLng(116.4);
        assertThrows(BizException.class, () -> service.quote(r));
    }
}