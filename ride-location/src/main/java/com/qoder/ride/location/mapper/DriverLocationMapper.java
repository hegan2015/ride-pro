package com.qoder.ride.location.mapper;

import com.qoder.ride.location.dataobject.DriverLocationDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DriverLocationMapper {

    /** 上报即覆盖（最新位置权威）。 */
    @Update("MERGE INTO driver_location(driver_id,lat,lng,updated_at) KEY(driver_id) "
            + "VALUES(#{driverId},#{lat},#{lng},#{updatedAt})")
    int upsert(DriverLocationDO location);

    @Select("SELECT * FROM driver_location WHERE driver_id=#{driverId}")
    DriverLocationDO selectById(String driverId);

    @Select("SELECT * FROM driver_location")
    List<DriverLocationDO> selectAll();

    /** 包围盒预过滤：先把候选缩到半径外接矩形，再精算 haversine。 */
    @Select("SELECT * FROM driver_location WHERE lat BETWEEN #{minLat} AND #{maxLat} "
            + "AND lng BETWEEN #{minLng} AND #{maxLng}")
    List<DriverLocationDO> selectInBox(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                       @Param("minLng") double minLng, @Param("maxLng") double maxLng);
}