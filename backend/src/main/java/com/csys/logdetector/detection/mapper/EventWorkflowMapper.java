package com.csys.logdetector.detection.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;
import java.util.List;
import java.util.Map;

/** 读写事件状态、处理时间线、导出数据和操作审计。 */
@Mapper
public interface EventWorkflowMapper {
    List<String> lockStatus(@Param("id") long id);
    void updateStatus(@Param("id") long id, @Param("status") String status);
    void insertStatusChange(@Param("id") long id, @Param("actorId") long actorId, @Param("oldStatus") String oldStatus, @Param("status") String status, @Param("note") String note);
    int countEvents(@Param("id") long id);
    List<Map<String, Object>> timeline(@Param("id") long id);
    void forEachExportRow(@Param("batchId") Long batchId, ResultHandler<Map<String, String>> handler);
    void audit(@Param("actorId") long actorId, @Param("action") String action, @Param("id") String id, @Param("traceId") String traceId);
}
