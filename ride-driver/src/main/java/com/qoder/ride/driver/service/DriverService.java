package com.qoder.ride.driver.service;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;
import com.qoder.ride.driver.dataobject.DriverDO;
import com.qoder.ride.driver.mapper.DriverMapper;
import com.qoder.ride.facade.driver.DriverCreateRequest;
import com.qoder.ride.facade.driver.DriverDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DriverService {

    private final DriverMapper driverMapper;

    public DriverDTO create(DriverCreateRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "司机姓名必填");
        }
        if (request.getCarNo() == null || request.getCarNo().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "车牌号必填");
        }
        DriverDO driver = new DriverDO();
        driver.setId("D" + driverMapper.nextId());
        driver.setName(request.getName());
        driver.setPhone(request.getPhone());
        driver.setCarNo(request.getCarNo());
        driver.setCarModel(request.getCarModel());
        driver.setStatus("OFFLINE");
        driver.setServiceType(normalizeServiceType(request.getServiceType()));
        driver.setServiceScore(5.0);
        driver.setDeclineCount(0);
        long now = System.currentTimeMillis();
        driver.setCreatedAt(now);
        driver.setUpdatedAt(now);
        driverMapper.insert(driver);
        log.info("司机注册 driverId={} name={} carNo={} serviceType={}",
                driver.getId(), driver.getName(), driver.getCarNo(), driver.getServiceType());
        return toDTO(driver);
    }

    private String normalizeServiceType(String serviceType) {
        if (serviceType == null || serviceType.isBlank()) {
            return "EXPRESS";
        }
        String upper = serviceType.toUpperCase();
        if (!"EXPRESS".equals(upper) && !"PREMIER".equals(upper) && !"TAXI".equals(upper)) {
            throw new BizException(ResultCode.PARAM_ERROR, "未知服务类型: " + serviceType);
        }
        return upper;
    }

    public DriverDTO get(String driverId) {
        return toDTO(require(driverId));
    }

    public List<DriverDTO> list() {
        return driverMapper.selectAll().stream().map(this::toDTO).toList();
    }

    public DriverDTO setOnline(String driverId, boolean online) {
        DriverDO driver = require(driverId);
        if (online && Boolean.TRUE.equals(driver.getBlocked())) {
            throw new BizException(ResultCode.CONFLICT, "司机已被封禁，禁止出车: " + driverId);
        }
        if ("BUSY".equals(driver.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "司机服务中，不可切换上下线: " + driverId);
        }
        String target = online ? "ONLINE" : "OFFLINE";
        driverMapper.updateStatus(driverId, target, System.currentTimeMillis());
        log.info("司机上下线 driverId={} status={}", driverId, target);
        return toDTO(require(driverId));
    }

    public DriverDTO setBlocked(String driverId, boolean blocked) {
        require(driverId);
        driverMapper.updateBlocked(driverId, blocked);
        if (blocked) {
            driverMapper.updateStatus(driverId, "OFFLINE", System.currentTimeMillis());
        }
        log.info("司机封禁 driverId={} blocked={}", driverId, blocked);
        return toDTO(require(driverId));
    }

    public DriverDTO setStatus(String driverId, String status) {
        require(driverId);
        if (!"ONLINE".equals(status) && !"BUSY".equals(status) && !"OFFLINE".equals(status)) {
            throw new BizException(ResultCode.PARAM_ERROR, "非法司机状态: " + status);
        }
        driverMapper.updateStatus(driverId, status, System.currentTimeMillis());
        log.info("司机状态变更 driverId={} status={}", driverId, status);
        return toDTO(require(driverId));
    }

    /** 抢单前置 CAS：ONLINE→BUSY 仅一次成功，杜绝同司机并发接两单。 */
    public boolean casStatus(String driverId, String from, String to) {
        require(driverId);
        if (!"ONLINE".equals(from) && !"BUSY".equals(from) && !"OFFLINE".equals(from)
                || !"ONLINE".equals(to) && !"BUSY".equals(to) && !"OFFLINE".equals(to)) {
            throw new BizException(ResultCode.PARAM_ERROR, "非法司机状态: " + from + "→" + to);
        }
        boolean ok = driverMapper.casStatus(driverId, from, to, System.currentTimeMillis()) > 0;
        if (ok) {
            log.info("司机状态 CAS driverId={} {}→{}", driverId, from, to);
        }
        return ok;
    }

    public void updateScore(String driverId, double score) {
        require(driverId);
        if (score < 1 || score > 5) {
            throw new BizException(ResultCode.PARAM_ERROR, "服务分需在 1~5");
        }
        driverMapper.updateScore(driverId, score);
        log.info("司机服务分更新 driverId={} score={}", driverId, score);
    }

    public void recordDecline(String driverId) {
        require(driverId);
        driverMapper.incrementDecline(driverId);
        log.info("司机拒单计数 driverId={}", driverId);
    }

    private DriverDO require(String driverId) {
        DriverDO driver = driverMapper.selectById(driverId);
        if (driver == null) {
            throw new BizException(ResultCode.NOT_FOUND, "司机不存在: " + driverId);
        }
        return driver;
    }

    private DriverDTO toDTO(DriverDO driver) {
        DriverDTO dto = new DriverDTO();
        dto.setId(driver.getId());
        dto.setName(driver.getName());
        dto.setPhone(driver.getPhone());
        dto.setCarNo(driver.getCarNo());
        dto.setCarModel(driver.getCarModel());
        dto.setStatus(driver.getStatus());
        dto.setServiceType(driver.getServiceType() == null ? "EXPRESS" : driver.getServiceType());
        dto.setServiceScore(driver.getServiceScore() == null ? 5.0 : driver.getServiceScore());
        dto.setDeclineCount(driver.getDeclineCount() == null ? 0 : driver.getDeclineCount());
        dto.setBlocked(Boolean.TRUE.equals(driver.getBlocked()));
        dto.setCreatedAt(driver.getCreatedAt());
        dto.setUpdatedAt(driver.getUpdatedAt());
        return dto;
    }
}