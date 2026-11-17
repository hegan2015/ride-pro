package com.qoder.ride.order.mapper;

import com.qoder.ride.order.dataobject.IdempotentRecordDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IdempotentMapper {

    @Select("SELECT * FROM idempotent_record WHERE request_id=#{requestId}")
    IdempotentRecordDO selectByRequestId(String requestId);

    @Insert("INSERT INTO idempotent_record(request_id,biz_type,result_json,created_at) "
            + "VALUES(#{requestId},#{bizType},#{resultJson},#{createdAt})")
    int insert(IdempotentRecordDO record);

    @Insert("MERGE INTO idempotent_record(request_id,biz_type,result_json,created_at) KEY(request_id) "
            + "VALUES(#{requestId},#{bizType},#{resultJson},#{createdAt})")
    int merge(IdempotentRecordDO record);
}