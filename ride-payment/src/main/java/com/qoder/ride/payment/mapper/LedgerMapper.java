package com.qoder.ride.payment.mapper;

import com.qoder.ride.payment.dataobject.LedgerEntryDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface LedgerMapper {

    @Select("SELECT NEXT VALUE FOR seq_ledger")
    long nextId();

    @Insert("INSERT INTO ledger_entry(id,user_id,order_id,biz_type,amount,balance_after,request_id,created_at,status,pp_receipt) "
            + "VALUES(#{id},#{userId},#{orderId},#{bizType},#{amount},#{balanceAfter},#{requestId},#{createdAt},#{status},#{ppReceipt})")
    int insert(LedgerEntryDO entry);

    /** 回执确认：PENDING → CONFIRMED，回填动账后余额与支付系统回执号。 */
    @Update("UPDATE ledger_entry SET status='CONFIRMED', balance_after=#{balanceAfter}, pp_receipt=#{ppReceipt} "
            + "WHERE id=#{id} AND status='PENDING'")
    int confirm(@Param("id") String id, @Param("balanceAfter") long balanceAfter, @Param("ppReceipt") String ppReceipt);

    @Update("UPDATE ledger_entry SET status='FAILED' WHERE id=#{id} AND status='PENDING'")
    int fail(String id);

    @Select("SELECT COUNT(*) FROM ledger_entry WHERE order_id=#{orderId} AND biz_type='PAY_DEBIT' AND status='CONFIRMED'")
    int countPayDebit(String orderId);

    @Select("SELECT COUNT(*) FROM ledger_entry WHERE order_id=#{orderId} AND biz_type='REFUND_CREDIT' AND status='CONFIRMED'")
    int countRefundCredit(String orderId);

    /** 累计已退金额（REFUND_CREDIT 为正数入账流水，仅统计回执确认）。 */
    @Select("SELECT COALESCE(SUM(amount),0) FROM ledger_entry WHERE order_id=#{orderId} AND biz_type='REFUND_CREDIT' AND status='CONFIRMED'")
    long sumRefundCredit(String orderId);

    /** 用户某类型流水自 since 起求和（风控日累计用；WITHDRAW 为负数）。 */
    @Select("SELECT COALESCE(SUM(amount),0) FROM ledger_entry "
            + "WHERE user_id=#{userId} AND biz_type=#{bizType} AND status='CONFIRMED' AND created_at>=#{since}")
    long sumUserBizSince(@Param("userId") String userId, @Param("bizType") String bizType, @Param("since") long since);

    /** 退款时回查原平台抽成（仅回执确认流水）。 */
    @Select("SELECT * FROM ledger_entry WHERE order_id=#{orderId} AND biz_type=#{bizType} AND status='CONFIRMED' LIMIT 1")
    LedgerEntryDO selectByOrderAndBizType(@Param("orderId") String orderId, @Param("bizType") String bizType);

    /** 按幂等键回查该笔操作的全部借贷腿（含各状态，调用方按 status 过滤）。 */
    @Select("SELECT * FROM ledger_entry WHERE request_id=#{requestId} ORDER BY id")
    List<LedgerEntryDO> selectByRequestId(String requestId);

    @SelectProvider(type = LedgerSqlProvider.class, method = "query")
    List<LedgerEntryDO> query(Map<String, Object> params);

    @SelectProvider(type = LedgerSqlProvider.class, method = "count")
    long count(Map<String, Object> params);

    /** 账面核对：逐户流水回放求和（仅 CONFIRMED，与钱包动账一一对应）。 */
    @Select("SELECT user_id, COALESCE(SUM(amount),0) AS ledger_sum, COUNT(*) AS entry_count "
            + "FROM ledger_entry WHERE status='CONFIRMED' GROUP BY user_id")
    List<Map<String, Object>> sumByUser();

    /** 资金统计：按类型聚合总额/笔数/今日金额（仅 CONFIRMED）。 */
    @Select("SELECT biz_type, COALESCE(SUM(amount),0) AS total_amount, COUNT(*) AS entry_count, "
            + "COALESCE(SUM(CASE WHEN created_at>=#{since} THEN amount ELSE 0 END),0) AS today_amount "
            + "FROM ledger_entry WHERE status='CONFIRMED' GROUP BY biz_type ORDER BY biz_type")
    List<Map<String, Object>> stats(@Param("since") long since);
}