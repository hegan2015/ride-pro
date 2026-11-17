package com.qoder.ride.payment.mapper;

import com.qoder.ride.payment.dataobject.PayOutboxDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PayOutboxMapper {

    @Insert("INSERT INTO pay_outbox(order_id,request_id,status,retries,next_retry_at,created_at) "
            + "VALUES(#{orderId},#{requestId},'NEW',0,0,#{createdAt})")
    int insert(PayOutboxDO row);

    @Select("SELECT * FROM pay_outbox WHERE status='NEW' AND next_retry_at<=#{now} ORDER BY created_at LIMIT #{limit}")
    List<PayOutboxDO> selectPending(@Param("now") long now, @Param("limit") int limit);

    @Update("UPDATE pay_outbox SET status='SENT' WHERE order_id=#{orderId}")
    int markSent(String orderId);

    @Update("UPDATE pay_outbox SET retries=retries+1, next_retry_at=#{nextRetryAt} WHERE order_id=#{orderId}")
    int markRetry(@Param("orderId") String orderId, @Param("nextRetryAt") long nextRetryAt);

    @Update("UPDATE pay_outbox SET status='DEAD' WHERE order_id=#{orderId} AND status='NEW'")
    int markDead(String orderId);

    @Select("SELECT * FROM pay_outbox WHERE status='DEAD' ORDER BY created_at")
    List<PayOutboxDO> selectDead();

    @Update("UPDATE pay_outbox SET status='NEW', retries=0, next_retry_at=0 "
            + "WHERE (order_id=#{key} OR request_id=#{key}) AND status='DEAD'")
    int replay(String key);

    @Select("SELECT COUNT(*) FROM pay_outbox WHERE status=#{status}")
    long countByStatus(String status);

    @Select("SELECT COALESCE(MAX(retries),0) FROM pay_outbox")
    int maxRetries();

    @Select("SELECT MIN(created_at) FROM pay_outbox WHERE status='NEW'")
    Long oldestPendingCreatedAt();
}