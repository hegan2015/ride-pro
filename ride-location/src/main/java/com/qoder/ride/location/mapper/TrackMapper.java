package com.qoder.ride.location.mapper;

import com.qoder.ride.location.dataobject.TrackPointDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TrackMapper {

    @Select("SELECT COALESCE(MAX(seq),0)+1 FROM trip_track WHERE order_id=#{orderId}")
    long nextSeq(String orderId);

    @Insert("INSERT INTO trip_track(order_id,seq,lat,lng,ts) VALUES(#{orderId},#{seq},#{lat},#{lng},#{ts})")
    int insert(TrackPointDO point);

    @Select("SELECT * FROM trip_track WHERE order_id=#{orderId} ORDER BY seq")
    List<TrackPointDO> selectByOrder(String orderId);
}