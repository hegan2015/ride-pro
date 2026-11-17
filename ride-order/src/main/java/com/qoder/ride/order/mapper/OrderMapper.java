package com.qoder.ride.order.mapper;

import com.qoder.ride.order.dataobject.OrderDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface OrderMapper {

    @Select("SELECT NEXT VALUE FOR seq_order")
    long nextId();

    @Insert("INSERT INTO ride_order(id,passenger_id,passenger_name,driver_id,driver_name,driver_car_no,"
            + "origin_name,origin_lat,origin_lng,dest_name,dest_lat,dest_lng,distance_km,estimate_fee,actual_fee,"
            + "status,request_id,cancel_reason,service_type,fare_json,actual_distance_km,actual_duration_min,"
            + "dispatch_round,offered_driver_ids,declined_driver_ids,cancel_fee,dispatch_radius,last_dispatch_at,"
            + "created_at,accepted_at,arrived_at,started_at,completed_at,paid_at,cancelled_at,expired_at,refunded_at) "
            + "VALUES(#{id},#{passengerId},#{passengerName},#{driverId},"
            + "#{driverName},#{driverCarNo},#{originName},#{originLat},#{originLng},#{destName},#{destLat},#{destLng},"
            + "#{distanceKm},#{estimateFee},#{actualFee},#{status},#{requestId},#{cancelReason},#{serviceType},#{fareJson},"
            + "#{actualDistanceKm},#{actualDurationMin},#{dispatchRound},#{offeredDriverIds},#{declinedDriverIds},"
            + "#{cancelFee},#{dispatchRadius},#{lastDispatchAt},#{createdAt},#{acceptedAt},#{arrivedAt},#{startedAt},"
            + "#{completedAt},#{paidAt},#{cancelledAt},#{expiredAt},#{refundedAt})")
    int insert(OrderDO order);

    @Select("SELECT * FROM ride_order WHERE id=#{id}")
    OrderDO selectById(String id);

    @Select("SELECT * FROM ride_order ORDER BY created_at DESC, id DESC")
    List<OrderDO> selectAll();

    @Select("SELECT * FROM ride_order WHERE passenger_id=#{passengerId} ORDER BY created_at DESC, id DESC")
    List<OrderDO> selectByPassenger(String passengerId);

    @Select("SELECT * FROM ride_order WHERE driver_id=#{driverId} ORDER BY created_at DESC, id DESC")
    List<OrderDO> selectByDriver(String driverId);

    @Select("SELECT * FROM ride_order WHERE status=#{status} ORDER BY created_at, id")
    List<OrderDO> selectByStatus(String status);

    /** 司机的活跃单（含待支付）：BUSY 司机回收判定依据。 */
    @Select("SELECT * FROM ride_order WHERE driver_id=#{driverId} "
            + "AND status IN ('ACCEPTED','ARRIVED','IN_PROGRESS','COMPLETED')")
    List<OrderDO> selectActiveByDriver(String driverId);

    /** 抢单 CAS：DISPATCHING→ACCEPTED，仅一个司机能成功。 */
    @Update("UPDATE ride_order SET status='ACCEPTED', driver_id=#{driverId}, driver_name=#{driverName}, "
            + "driver_car_no=#{carNo}, accepted_at=#{now} WHERE id=#{id} AND status='DISPATCHING'")
    int accept(@Param("id") String id, @Param("driverId") String driverId,
               @Param("driverName") String driverName, @Param("carNo") String carNo, @Param("now") long now);

    @Update("UPDATE ride_order SET status='ARRIVED', arrived_at=#{now} "
            + "WHERE id=#{id} AND status='ACCEPTED' AND driver_id=#{driverId}")
    int arrive(@Param("id") String id, @Param("driverId") String driverId, @Param("now") long now);

    @Update("UPDATE ride_order SET status='IN_PROGRESS', started_at=#{now} "
            + "WHERE id=#{id} AND status='ARRIVED' AND driver_id=#{driverId}")
    int startTrip(@Param("id") String id, @Param("driverId") String driverId, @Param("now") long now);

    @Update("UPDATE ride_order SET status='COMPLETED', actual_fee=#{fee}, actual_distance_km=#{distanceKm}, "
            + "actual_duration_min=#{durationMin}, actual_fare_json=#{fareJson}, completed_at=#{now} "
            + "WHERE id=#{id} AND status='IN_PROGRESS' AND driver_id=#{driverId}")
    int completeTrip(@Param("id") String id, @Param("driverId") String driverId,
                     @Param("fee") long fee, @Param("distanceKm") double distanceKm,
                     @Param("durationMin") long durationMin, @Param("fareJson") String fareJson, @Param("now") long now);

    @Update("UPDATE ride_order SET status='PAID', paid_at=#{now} WHERE id=#{id} AND status='COMPLETED'")
    int pay(@Param("id") String id, @Param("now") long now);

    /** 取消：行程开始前（CREATED/DISPATCHING/ACCEPTED/ARRIVED）可取消。 */
    @Update("UPDATE ride_order SET status='CANCELLED', cancel_reason=#{reason}, cancel_fee=#{fee}, cancelled_at=#{now} "
            + "WHERE id=#{id} AND status IN ('CREATED','DISPATCHING','ACCEPTED','ARRIVED')")
    int cancel(@Param("id") String id, @Param("reason") String reason, @Param("fee") long fee, @Param("now") long now);

    @Update("UPDATE ride_order SET cancel_fee=#{fee} WHERE id=#{id}")
    int updateCancelFee(@Param("id") String id, @Param("fee") long fee);

    /** 司机取消：回 DISPATCHING 重新派单，清空司机快照，拒单名单追加该司机。 */
    @Update("UPDATE ride_order SET status='DISPATCHING', driver_id=NULL, driver_name=NULL, driver_car_no=NULL, "
            + "accepted_at=NULL, arrived_at=NULL, cancel_reason=#{reason}, dispatch_radius=#{radius}, "
            + "last_dispatch_at=0, declined_driver_ids=#{declined} "
            + "WHERE id=#{id} AND status IN ('ACCEPTED','ARRIVED') AND driver_id=#{driverId}")
    int driverCancel(@Param("id") String id, @Param("driverId") String driverId, @Param("reason") String reason,
                     @Param("radius") double radius, @Param("declined") String declined);

    /** 无人接单超轮次：DISPATCHING→EXPIRED 终态。 */
    @Update("UPDATE ride_order SET status='EXPIRED', expired_at=#{now} WHERE id=#{id} AND status='DISPATCHING'")
    int expire(@Param("id") String id, @Param("now") long now);

    /** 管理端退款终态：PAID→REFUNDED（累计退满后由 finalizeRefund 调用）。 */
    @Update("UPDATE ride_order SET status='REFUNDED', cancel_reason=#{reason}, refunded_at=#{now} "
            + "WHERE id=#{id} AND status='PAID'")
    int refund(@Param("id") String id, @Param("reason") String reason, @Param("now") long now);

    /** 退款对账同步：以支付域流水为权威只增不减，仅 PAID 态（部分退款保持 PAID）。 */
    @Update("UPDATE ride_order SET refunded_amount=#{total}, refund_count=#{count} "
            + "WHERE id=#{id} AND status='PAID' AND (COALESCE(refunded_amount,0)<#{total} OR COALESCE(refund_count,0)<#{count})")
    int syncRefund(@Param("id") String id, @Param("total") long total, @Param("count") int count);

    /** 催付/自动扣款尝试标记（每单一次催付，自动扣款按间隔重试）。 */
    @Update("UPDATE ride_order SET reminded_at=#{now} WHERE id=#{id} AND status='COMPLETED'")
    int markReminded(@Param("id") String id, @Param("now") long now);

    /** 管理端强制取消：含行程中的异常单关闭。 */
    @Update("UPDATE ride_order SET status='CANCELLED', cancel_reason=#{reason}, cancelled_at=#{now} "
            + "WHERE id=#{id} AND status IN ('CREATED','DISPATCHING','ACCEPTED','ARRIVED','IN_PROGRESS')")
    int forceCancel(@Param("id") String id, @Param("reason") String reason, @Param("now") long now);

    /** 改派：刷新本轮派单时间与半径，轮次 +1（仅 DISPATCHING）。 */
    @Update("UPDATE ride_order SET last_dispatch_at=#{now}, dispatch_radius=#{radius}, "
            + "dispatch_round=COALESCE(dispatch_round,0)+1 WHERE id=#{id} AND status='DISPATCHING'")
    int markDispatched(@Param("id") String id, @Param("now") long now, @Param("radius") double radius);

    @Update("UPDATE ride_order SET offered_driver_ids=#{offered} WHERE id=#{id}")
    int updateOffered(@Param("id") String id, @Param("offered") String offered);

    @Update("UPDATE ride_order SET declined_driver_ids=#{declined} WHERE id=#{id}")
    int updateDeclined(@Param("id") String id, @Param("declined") String declined);

    @SelectProvider(type = OrderSqlProvider.class, method = "query")
    List<OrderDO> query(Map<String, Object> params);

    @SelectProvider(type = OrderSqlProvider.class, method = "count")
    long count(Map<String, Object> params);

    @Select("SELECT status, COUNT(*) AS cnt FROM ride_order GROUP BY status")
    List<Map<String, Object>> statusCounts();

    @Select("SELECT COALESCE(SUM(actual_fee),0) FROM ride_order WHERE status='PAID'")
    long totalGmv();

    @Select("SELECT COALESCE(SUM(actual_fee),0) FROM ride_order WHERE status='PAID' AND paid_at>=#{since}")
    long gmvSince(@Param("since") long since);

    @Select("SELECT COUNT(*) FROM ride_order WHERE created_at>=#{since}")
    long countSince(@Param("since") long since);

    @Select("SELECT COUNT(*) FROM ride_order WHERE status='PAID' AND paid_at>=#{since}")
    long paidSince(@Param("since") long since);

    @Select("SELECT AVG(accepted_at - created_at) FROM ride_order WHERE accepted_at IS NOT NULL")
    Long avgAcceptMs();

    @Select("SELECT COUNT(*) FROM ride_order WHERE accepted_at IS NOT NULL")
    long everAccepted();
}