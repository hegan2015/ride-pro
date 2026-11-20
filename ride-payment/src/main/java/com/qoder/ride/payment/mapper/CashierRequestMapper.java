package com.qoder.ride.payment.mapper;

import com.qoder.ride.payment.dataobject.CashierRequestDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 收银台受理留痕：requestId 主键幂等，重试 upsert 复用同一行（不新增幂等键）。 */
@Mapper
public interface CashierRequestMapper {

    @Insert("INSERT INTO cashier_request(request_id,order_id,user_id,pp_order_id,pp_account_id,amount,mode,method,"
            + "code,card_no,verify_type,verify_token,status,pp_payment_id,last_error,created_at,updated_at) "
            + "VALUES(#{requestId},#{orderId},#{userId},#{ppOrderId},#{ppAccountId},#{amount},#{mode},#{method},"
            + "#{code},#{cardNo},#{verifyType},#{verifyToken},#{status},#{ppPaymentId},#{lastError},#{createdAt},#{updatedAt}) "
            + "ON DUPLICATE KEY UPDATE pp_order_id=VALUES(pp_order_id), pp_account_id=VALUES(pp_account_id), "
            + "amount=VALUES(amount), mode=VALUES(mode), method=VALUES(method), code=VALUES(code), "
            + "card_no=VALUES(card_no), verify_type=VALUES(verify_type), verify_token=VALUES(verify_token), "
            + "status=VALUES(status), last_error=NULL, updated_at=VALUES(updated_at)")
    int upsert(CashierRequestDO row);

    @Select("SELECT * FROM cashier_request WHERE request_id=#{requestId}")
    CashierRequestDO selectByRequestId(String requestId);

    @Update("UPDATE cashier_request SET status='SENT', pp_payment_id=#{ppPaymentId}, last_error=NULL, updated_at=#{now} "
            + "WHERE request_id=#{requestId}")
    int markSent(@Param("requestId") String requestId, @Param("ppPaymentId") String ppPaymentId, @Param("now") long now);

    @Update("UPDATE cashier_request SET status='FAILED', last_error=#{lastError}, updated_at=#{now} WHERE request_id=#{requestId}")
    int markFailed(@Param("requestId") String requestId, @Param("lastError") String lastError, @Param("now") long now);
}