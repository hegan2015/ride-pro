package com.qoder.ride.payment.mapper;

import com.qoder.ride.payment.dataobject.CashierCodeDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 收银台付款码留痕：code 主键，状态随 pp 查询同步（只更新状态，不删既有行）。 */
@Mapper
public interface CashierCodeMapper {

    @Insert("INSERT INTO cashier_code(code,order_id,user_id,pp_order_id,pp_account_id,type,amount,status,"
            + "request_id,expire_at,created_at,updated_at) "
            + "VALUES(#{code},#{orderId},#{userId},#{ppOrderId},#{ppAccountId},#{type},#{amount},#{status},"
            + "#{requestId},#{expireAt},#{createdAt},#{updatedAt}) "
            + "ON DUPLICATE KEY UPDATE status=VALUES(status), expire_at=VALUES(expire_at), updated_at=VALUES(updated_at)")
    int upsert(CashierCodeDO row);

    @Select("SELECT * FROM cashier_code WHERE code=#{code}")
    CashierCodeDO selectByCode(String code);

    @Select("SELECT * FROM cashier_code WHERE order_id=#{orderId} ORDER BY created_at DESC")
    List<CashierCodeDO> selectByOrder(String orderId);

    @Update("UPDATE cashier_code SET status=#{status}, updated_at=#{now} WHERE code=#{code}")
    int updateStatus(@Param("code") String code, @Param("status") String status, @Param("now") long now);
}