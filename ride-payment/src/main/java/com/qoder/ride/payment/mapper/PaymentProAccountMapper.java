package com.qoder.ride.payment.mapper;

import com.qoder.ride.payment.dataobject.PaymentProAccountDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PaymentProAccountMapper {

    @Select("SELECT * FROM payment_pro_account WHERE user_id=#{userId}")
    PaymentProAccountDO selectByUserId(String userId);

    @Insert("INSERT INTO payment_pro_account(user_id,member_id,account_id,created_at,pp_balance_snapshot) "
            + "VALUES(#{userId},#{memberId},#{accountId},#{createdAt},#{ppBalanceSnapshot})")
    int insert(PaymentProAccountDO row);

    @Update("UPDATE payment_pro_account SET pp_balance_snapshot=#{snapshot} WHERE user_id=#{userId}")
    int updateSnapshot(@Param("userId") String userId, @Param("snapshot") long snapshot);

    @Select("SELECT * FROM payment_pro_account ORDER BY created_at")
    List<PaymentProAccountDO> selectAll();
}