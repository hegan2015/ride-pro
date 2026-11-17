package com.qoder.ride.payment.mapper;

import com.qoder.ride.payment.dataobject.RefundOutboxDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RefundOutboxMapper {

    @Insert("INSERT INTO refund_outbox(request_id,order_id,status,retries,next_retry_at,created_at) "
            + "VALUES(#{requestId},#{orderId},'NEW',0,0,#{createdAt})")
    int insert(RefundOutboxDO row);

    @Select("SELECT * FROM refund_outbox WHERE status='NEW' AND next_retry_at<=#{now} ORDER BY created_at LIMIT #{limit}")
    List<RefundOutboxDO> selectPending(@Param("now") long now, @Param("limit") int limit);

    @Update("UPDATE refund_outbox SET status='SENT' WHERE request_id=#{requestId}")
    int markSent(String requestId);

    @Update("UPDATE refund_outbox SET retries=retries+1, next_retry_at=#{nextRetryAt} WHERE request_id=#{requestId}")
    int markRetry(@Param("requestId") String requestId, @Param("nextRetryAt") long nextRetryAt);

    @Update("UPDATE refund_outbox SET status='DEAD' WHERE request_id=#{requestId} AND status='NEW'")
    int markDead(String requestId);

    @Select("SELECT COUNT(*) FROM refund_outbox WHERE status=#{status}")
    long countByStatus(String status);

    @Select("SELECT COALESCE(MAX(retries),0) FROM refund_outbox")
    int maxRetries();

    @Select("SELECT MIN(created_at) FROM refund_outbox WHERE status='NEW'")
    Long oldestPendingCreatedAt();

    @Select("SELECT * FROM refund_outbox WHERE status='DEAD' ORDER BY created_at")
    List<RefundOutboxDO> selectDead();

    @Update("UPDATE refund_outbox SET status='NEW', retries=0, next_retry_at=0 "
            + "WHERE (order_id=#{key} OR request_id=#{key}) AND status='DEAD'")
    int replay(String key);
}