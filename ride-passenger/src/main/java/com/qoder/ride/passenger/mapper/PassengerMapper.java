package com.qoder.ride.passenger.mapper;

import com.qoder.ride.passenger.dataobject.PassengerDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface PassengerMapper {

    long nextId();

    @Insert("INSERT INTO passenger(id,name,phone,created_at) VALUES(#{id},#{name},#{phone},#{createdAt})")
    int insert(PassengerDO passenger);

    @Select("SELECT * FROM passenger WHERE id=#{id}")
    PassengerDO selectById(String id);

    @Select("SELECT * FROM passenger ORDER BY created_at, id")
    List<PassengerDO> selectAll();

    @Update("UPDATE passenger SET auto_pay=#{enabled} WHERE id=#{id}")
    int updateAutoPay(@Param("id") String id, @Param("enabled") boolean enabled);
}