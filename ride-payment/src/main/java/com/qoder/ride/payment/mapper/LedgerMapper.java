package com.qoder.ride.payment.mapper;

import com.qoder.ride.payment.dataobject.LedgerEntryDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;
import java.util.Map;

@Mapper
public interface LedgerMapper {

    @Select("SELECT NEXT VALUE FOR seq_ledger")
    long nextId();

    @Insert("INSERT INTO ledger_entry(id,user_id,order_id,biz_type,amount,balance_after,request_id,created_at) "
            + "VALUES(#{id},#{userId},#{orderId},#{bizType},#{amount},#{balanceAfter},#{requestId},#{createdAt})")
    int insert(LedgerEntryDO entry);

    @Select("SELECT COUNT(*) FROM ledger_entry WHERE order_id=#{orderId} AND biz_type='PAY_DEBIT'")
    int countPayDebit(String orderId);

    @Select("SELECT COUNT(*) FROM ledger_entry WHERE order_id=#{orderId} AND biz_type='REFUND_CREDIT'")
    int countRefundCredit(String orderId);

    /** 累计已退金额（REFUND_CREDIT 为正数入账流水）。 */
    @Select("SELECT COALESCE(SUM(amount),0) FROM ledger_entry WHERE order_id=#{orderId} AND biz_type='REFUND_CREDIT'")
    long sumRefundCredit(String orderId);

    /** 用户某类型流水自 since 起求和（风控日累计用；WITHDRAW 为负数）。 */
    @Select("SELECT COALESCE(SUM(amount),0) FROM ledger_entry "
            + "WHERE user_id=#{userId} AND biz_type=#{bizType} AND created_at>=#{since}")
    long sumUserBizSince(@Param("userId") String userId, @Param("bizType") String bizType, @Param("since") long since);

    /** 退款时回查原平台抽成（与支付同事务落库）。 */
    @Select("SELECT * FROM ledger_entry WHERE order_id=#{orderId} AND biz_type=#{bizType} LIMIT 1")
    LedgerEntryDO selectByOrderAndBizType(@Param("orderId") String orderId, @Param("bizType") String bizType);

    /** 双账本镜像：按幂等键回查该笔操作的全部借贷腿。 */
    @Select("SELECT * FROM ledger_entry WHERE request_id=#{requestId} ORDER BY id")
    List<LedgerEntryDO> selectByRequestId(String requestId);

    @SelectProvider(type = LedgerSqlProvider.class, method = "query")
    List<LedgerEntryDO> query(Map<String, Object> params);

    @SelectProvider(type = LedgerSqlProvider.class, method = "count")
    long count(Map<String, Object> params);

    /** 账面核对：逐户流水回放求和。 */
    @Select("SELECT user_id, COALESCE(SUM(amount),0) AS ledger_sum, COUNT(*) AS entry_count "
            + "FROM ledger_entry GROUP BY user_id")
    List<Map<String, Object>> sumByUser();

    /** 资金统计：按类型聚合总额/笔数/今日金额。 */
    @Select("SELECT biz_type, COALESCE(SUM(amount),0) AS total_amount, COUNT(*) AS entry_count, "
            + "COALESCE(SUM(CASE WHEN created_at>=#{since} THEN amount ELSE 0 END),0) AS today_amount "
            + "FROM ledger_entry GROUP BY biz_type ORDER BY biz_type")
    List<Map<String, Object>> stats(@Param("since") long since);
}