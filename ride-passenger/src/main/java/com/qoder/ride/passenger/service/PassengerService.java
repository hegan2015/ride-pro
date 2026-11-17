package com.qoder.ride.passenger.service;

import com.qoder.ride.common.exception.BizException;
import com.qoder.ride.common.result.ResultCode;
import com.qoder.ride.facade.passenger.PassengerCreateRequest;
import com.qoder.ride.facade.passenger.PassengerDTO;
import com.qoder.ride.passenger.dataobject.PassengerDO;
import com.qoder.ride.passenger.mapper.PassengerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PassengerService {

    private final PassengerMapper passengerMapper;

    public PassengerDTO create(PassengerCreateRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "乘客姓名必填");
        }
        PassengerDO passenger = new PassengerDO();
        passenger.setId("P" + passengerMapper.nextId());
        passenger.setName(request.getName());
        passenger.setPhone(request.getPhone());
        passenger.setCreatedAt(System.currentTimeMillis());
        passengerMapper.insert(passenger);
        log.info("乘客注册 passengerId={} name={}", passenger.getId(), passenger.getName());
        return toDTO(passenger);
    }

    public PassengerDTO get(String passengerId) {
        return toDTO(require(passengerId));
    }

    /** 免密自动扣款授权开关：行程结束宽限期后由订单域服务端自动支付。 */
    public PassengerDTO setAutoPay(String passengerId, boolean enabled) {
        require(passengerId);
        passengerMapper.updateAutoPay(passengerId, enabled);
        log.info("乘客免密支付授权 passengerId={} enabled={}", passengerId, enabled);
        return toDTO(require(passengerId));
    }

    public List<PassengerDTO> list() {
        return passengerMapper.selectAll().stream().map(this::toDTO).toList();
    }

    private PassengerDO require(String passengerId) {
        PassengerDO passenger = passengerMapper.selectById(passengerId);
        if (passenger == null) {
            throw new BizException(ResultCode.NOT_FOUND, "乘客不存在: " + passengerId);
        }
        return passenger;
    }

    private PassengerDTO toDTO(PassengerDO passenger) {
        PassengerDTO dto = new PassengerDTO();
        dto.setId(passenger.getId());
        dto.setName(passenger.getName());
        dto.setPhone(passenger.getPhone());
        dto.setAutoPay(Boolean.TRUE.equals(passenger.getAutoPay()));
        dto.setCreatedAt(passenger.getCreatedAt());
        return dto;
    }
}