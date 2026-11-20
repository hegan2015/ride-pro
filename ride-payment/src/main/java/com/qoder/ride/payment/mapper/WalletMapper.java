package com.qoder.ride.payment.mapper;

import com.qoder.ride.payment.dataobject.WalletDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WalletMapper {

    /** 钱包不存在则建零余额钱包（幂等，不覆盖已有余额）。 */
    @Insert("INSERT INTO wallet(user_id,balance,updated_at) "
            + "SELECT #{userId},0,#{now} WHERE NOT EXISTS(SELECT 1 FROM wallet WHERE user_id=#{userId})")
    int ensure(@Param("userId") String userId, @Param("now") long now);

    @Select("SELECT * FROM wallet WHERE user_id=#{userId}")
    WalletDO selectByUserId(String userId);

    @Select("SELECT * FROM wallet ORDER BY user_id")
    List<WalletDO> selectAll();

    /** 扣款带可用余额守卫：balance-frozen>=amount 才成功，杜绝并发透支与动用冻结资金。 */
    @Update("UPDATE wallet SET balance=balance-#{amount}, updated_at=#{now} "
            + "WHERE user_id=#{userId} AND balance-frozen_amount>=#{amount}")
    int debit(@Param("userId") String userId, @Param("amount") long amount, @Param("now") long now);

    @Update("UPDATE wallet SET balance=balance+#{amount}, updated_at=#{now} WHERE user_id=#{userId}")
    int credit(@Param("userId") String userId, @Param("amount") long amount, @Param("now") long now);

    /**
     * 外部资金清算户扣款（无余额守卫，允许透支）：银行卡支付的出资方是外部银行，
     * 对标 payment-pro 的 SYS_CASH，余额为负表示待清算的外部资金，不是用户钱包。
     * 仅限系统清算户使用，严禁用于任何用户钱包。
     */
    @Update("UPDATE wallet SET balance=balance-#{amount}, updated_at=#{now} WHERE user_id=#{userId}")
    int debitClearing(@Param("userId") String userId, @Param("amount") long amount, @Param("now") long now);

    /** 提现冻结：可用余额守卫，balance 不变（审计守恒：冻结不落流水）。 */
    @Update("UPDATE wallet SET frozen_amount=frozen_amount+#{amount}, updated_at=#{now} "
            + "WHERE user_id=#{userId} AND balance-frozen_amount>=#{amount}")
    int freeze(@Param("userId") String userId, @Param("amount") long amount, @Param("now") long now);

    /** 提现失败解冻：balance 不变。 */
    @Update("UPDATE wallet SET frozen_amount=frozen_amount-#{amount}, updated_at=#{now} "
            + "WHERE user_id=#{userId} AND frozen_amount>=#{amount}")
    int unfreeze(@Param("userId") String userId, @Param("amount") long amount, @Param("now") long now);

    /** 提现成功：扣款+解冻原子完成（与 WITHDRAW 流水同事务）。 */
    @Update("UPDATE wallet SET balance=balance-#{amount}, frozen_amount=frozen_amount-#{amount}, updated_at=#{now} "
            + "WHERE user_id=#{userId} AND frozen_amount>=#{amount} AND balance>=#{amount}")
    int debitAndUnfreeze(@Param("userId") String userId, @Param("amount") long amount, @Param("now") long now);
}