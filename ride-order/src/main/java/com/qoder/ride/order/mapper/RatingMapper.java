package com.qoder.ride.order.mapper;

import com.qoder.ride.order.dataobject.RatingDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RatingMapper {

    @Insert("INSERT INTO order_rating(order_id,role,score,comment,tags,anonymous,created_at) "
            + "VALUES(#{orderId},#{role},#{score},#{comment},#{tags},#{anonymous},#{createdAt})")
    int insert(RatingDO rating);

    @Select("SELECT * FROM order_rating WHERE order_id=#{orderId} AND role=#{role}")
    RatingDO selectByOrderAndRole(@Param("orderId") String orderId, @Param("role") String role);

    @Select("SELECT * FROM order_rating WHERE order_id=#{orderId} ORDER BY role")
    List<RatingDO> selectByOrder(String orderId);

    /** 司机服务分 = 最近 20 条乘客评价均分。 */
    @Select("SELECT AVG(score) FROM (SELECT r.score FROM order_rating r JOIN ride_order o ON r.order_id=o.id "
            + "WHERE o.driver_id=#{driverId} AND r.role='passenger' ORDER BY r.created_at DESC LIMIT 20) t")
    Double avgScoreForDriver(String driverId);
}