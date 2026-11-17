package com.qoder.ride.payment.mapper;

import com.qoder.ride.payment.dataobject.PaymentProNotifyDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentProNotifyMapper {

    @Insert("INSERT INTO payment_pro_notify(event,biz_id,payload,created_at) VALUES(#{event},#{bizId},#{payload},#{createdAt})")
    int insert(PaymentProNotifyDO row);
}