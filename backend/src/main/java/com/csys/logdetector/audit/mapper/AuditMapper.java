package com.csys.logdetector.audit.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditMapper {
    long count();
    List<Map<String, Object>> list(@Param("limit") long limit, @Param("offset") long offset);
    int insert(@Param("actorId") Long actorId, @Param("action") String action,
               @Param("targetType") String targetType, @Param("targetId") String targetId,// 目标类型和目标 ID 用于记录操作的范围, 例如: 日志批次 ID, 日志行 ID 等
               @Param("traceId") String traceId);//trace ID 用于关联操作, 例如: 一次导入操作, 可以关联多个日志行操作
}

