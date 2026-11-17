package com.qoder.ride.payment.mapper;

import com.qoder.ride.payment.dataobject.MirrorOutboxDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MirrorOutboxMapper {

    @Insert("INSERT INTO mirror_outbox(biz_no,biz_type,ref_key,user_id,order_id,status,retries,next_retry_at,pp_order_id,pp_payment_id,last_error,created_at) "
            + "VALUES(#{bizNo},#{bizType},#{refKey},#{userId},#{orderId},#{status},#{retries},#{nextRetryAt},#{ppOrderId},#{ppPaymentId},#{lastError},#{createdAt})")
    int insert(MirrorOutboxDO row);

    /** 拒绝后重试：FAILED 记录重置为 NEW 重新驱动（幂等占位已清除，不会与在途请求冲突）。 */
    @Insert("MERGE INTO mirror_outbox(biz_no,biz_type,ref_key,user_id,order_id,status,retries,next_retry_at,pp_order_id,pp_payment_id,last_error,created_at) "
            + "KEY(biz_no) VALUES(#{bizNo},#{bizType},#{refKey},#{userId},#{orderId},#{status},#{retries},#{nextRetryAt},#{ppOrderId},#{ppPaymentId},#{lastError},#{createdAt})")
    int upsert(MirrorOutboxDO row);

    @Select("SELECT * FROM mirror_outbox WHERE biz_no=#{bizNo}")
    MirrorOutboxDO selectByBizNo(String bizNo);

    @Select("SELECT * FROM mirror_outbox WHERE status='NEW' AND next_retry_at <= #{now} ORDER BY created_at LIMIT #{limit}")
    List<MirrorOutboxDO> selectPending(@Param("now") long now, @Param("limit") int limit);

    @Update("UPDATE mirror_outbox SET status='SENT' WHERE biz_no=#{bizNo}")
    int markSent(String bizNo);

    /** 支付系统明确拒绝：操作终态失败（本地流水 FAILED、冻结已退回），区别于可重试的 DEAD。 */
    @Update("UPDATE mirror_outbox SET status='FAILED', last_error=#{lastError} WHERE biz_no=#{bizNo} AND status='NEW'")
    int markFailed(@Param("bizNo") String bizNo, @Param("lastError") String lastError);

    @Update("UPDATE mirror_outbox SET retries=retries+1, next_retry_at=#{nextRetryAt}, last_error=#{lastError} WHERE biz_no=#{bizNo}")
    int markRetry(@Param("bizNo") String bizNo, @Param("nextRetryAt") long nextRetryAt, @Param("lastError") String lastError);

    @Update("UPDATE mirror_outbox SET status='DEAD', retries=retries+1, last_error=#{lastError} WHERE biz_no=#{bizNo}")
    int markDead(@Param("bizNo") String bizNo, @Param("lastError") String lastError);

    @Update("UPDATE mirror_outbox SET pp_order_id=#{ppOrderId}, pp_payment_id=#{ppPaymentId} WHERE biz_no=#{bizNo}")
    int updatePp(@Param("bizNo") String bizNo, @Param("ppOrderId") String ppOrderId, @Param("ppPaymentId") String ppPaymentId);

    @Select("SELECT COUNT(*) FROM mirror_outbox WHERE status='NEW' AND user_id=#{userId}")
    int countPendingByUser(String userId);

    @Select("SELECT COUNT(*) FROM mirror_outbox WHERE status='NEW'")
    int countPending();

    @Select("SELECT COUNT(*) FROM mirror_outbox WHERE status='DEAD'")
    int countDead();

    @Select("SELECT COUNT(*) FROM mirror_outbox WHERE status='FAILED'")
    int countFailed();

    @Select("SELECT COUNT(*) FROM mirror_outbox WHERE status='SENT'")
    int countSent();

    @Select("SELECT * FROM mirror_outbox WHERE status='SENT' ORDER BY created_at")
    List<MirrorOutboxDO> selectSent();

    @Select("SELECT MIN(created_at) FROM mirror_outbox WHERE status='NEW'")
    Long oldestPendingCreatedAt();

    @Select("SELECT * FROM mirror_outbox WHERE status='DEAD' ORDER BY created_at")
    List<MirrorOutboxDO> selectDead();

    @Update("UPDATE mirror_outbox SET status='NEW', retries=0, next_retry_at=0, last_error=NULL WHERE biz_no=#{bizNo} AND status='DEAD'")
    int replay(String bizNo);

    @Update("UPDATE mirror_outbox SET status='CLOSED', last_error=#{reason} WHERE biz_no=#{bizNo} AND status='DEAD'")
    int close(@Param("bizNo") String bizNo, @Param("reason") String reason);
}