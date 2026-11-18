package com.qoder.ride.driver.mapper;

import com.qoder.ride.driver.dataobject.DriverDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DriverMapper {

    long nextId();

    @Insert("INSERT INTO driver(id,name,phone,car_no,car_model,status,service_type,service_score,decline_count,created_at,updated_at) "
            + "VALUES(#{id},#{name},#{phone},#{carNo},#{carModel},#{status},#{serviceType},#{serviceScore},#{declineCount},#{createdAt},#{updatedAt})")
    int insert(DriverDO driver);

    @Select("SELECT * FROM driver WHERE id=#{id}")
    DriverDO selectById(String id);

    @Select("SELECT * FROM driver ORDER BY created_at, id")
    List<DriverDO> selectAll();

    @Update("UPDATE driver SET status=#{status}, updated_at=#{now} WHERE id=#{id}")
    int updateStatus(@Param("id") String id, @Param("status") String status, @Param("now") long now);

    /** CAS：仅当处于期望旧态时翻转，保证并发安全（如 ONLINE→BUSY 只能成功一次）。 */
    @Update("UPDATE driver SET status=#{to}, updated_at=#{now} WHERE id=#{id} AND status=#{from}")
    int casStatus(@Param("id") String id, @Param("from") String from, @Param("to") String to, @Param("now") long now);

    @Update("UPDATE driver SET service_score=#{score} WHERE id=#{id}")
    int updateScore(@Param("id") String id, @Param("score") double score);

    @Update("UPDATE driver SET decline_count=COALESCE(decline_count,0)+1 WHERE id=#{id}")
    int incrementDecline(String id);
}