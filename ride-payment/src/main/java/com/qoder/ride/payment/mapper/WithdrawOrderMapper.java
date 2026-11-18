package com.qoder.ride.payment.mapper;

import com.qoder.ride.payment.dataobject.WithdrawOrderDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WithdrawOrderMapper {

    long nextId();

    @Insert("INSERT INTO withdraw_order(id,user_id,amount,status,biz_no,fail_reason,created_at,updated_at) "
            + "VALUES(#{id},#{userId},#{amount},#{status},#{bizNo},#{failReason},#{createdAt},#{updatedAt})")
    int insert(WithdrawOrderDO row);

    @Select("SELECT * FROM withdraw_order WHERE user_id=#{userId} ORDER BY created_at DESC, id DESC")
    List<WithdrawOrderDO> selectByUser(String userId);

    @Select("SELECT * FROM withdraw_order WHERE biz_no=#{bizNo}")
    WithdrawOrderDO selectByBizNo(String bizNo);

    @Select("SELECT * FROM withdraw_order WHERE status=#{status} ORDER BY created_at LIMIT #{limit}")
    List<WithdrawOrderDO> selectByStatus(@Param("status") String status, @Param("limit") int limit);

    /** 处理中且已停留超过渠道模拟时长的单据（崩溃后重驱动同样走这里，CAS 保证只完成一次）。 */
    @Select("SELECT * FROM withdraw_order WHERE status='PROCESSING' AND updated_at<=#{before} ORDER BY updated_at LIMIT #{limit}")
    List<WithdrawOrderDO> selectProcessable(@Param("before") long before, @Param("limit") int limit);

    @Update("UPDATE withdraw_order SET status=#{to}, updated_at=#{now} WHERE id=#{id} AND status=#{from}")
    int casStatus(@Param("id") String id, @Param("from") String from, @Param("to") String to, @Param("now") long now);

    @Update("UPDATE withdraw_order SET status='FAILED', fail_reason=#{reason}, updated_at=#{now} "
            + "WHERE id=#{id} AND status='PROCESSING'")
    int fail(@Param("id") String id, @Param("reason") String reason, @Param("now") long now);

    @Select("SELECT COUNT(*) FROM withdraw_order WHERE user_id=#{userId} AND created_at>=#{since}")
    long countSince(@Param("userId") String userId, @Param("since") long since);

    /** 当日非失败提现单金额合计（受理即占用额度，防并发绕过日累计限额）。 */
    @Select("SELECT COALESCE(SUM(amount),0) FROM withdraw_order "
            + "WHERE user_id=#{userId} AND created_at>=#{since} AND status<>'FAILED'")
    long sumActiveSince(@Param("userId") String userId, @Param("since") long since);
}