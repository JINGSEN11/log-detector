package com.csys.logdetector.detection.mapper;

import com.csys.logdetector.detection.engine.DetectionEngine.Log;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/** 读写批次检测所需数据。 */
@Mapper
public interface DetectionMapper {
    Long lockBatch(long id);
    long detectedCount(long id);
    List<Log> logs(long id);
    int registerRule(String code);
    int insertEvent(Map<String, Object> row);
    int insertHit(Map<String, Object> row);
    record Evidence(long eventId, long hitId, int primary) {}
    int insertEvidence(@Param("rows") List<Evidence> rows);
    int markDetected(long id);
    List<Map<String, Object>> events(@Param("batchId") Long batchId);
    List<Map<String, Object>> event(long id);
    List<Map<String, Object>> evidence(long id);
}
