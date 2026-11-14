package com.csys.logdetector.detection.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 查询仪表盘统计和日志上下文。 */
@Mapper
public interface DashboardMapper {
    List<LocalDateTime> eventWindowStarts(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until);
    Long eventCount();
    Long highSeverityEventCount();
    Long logCount();
    Long aiAnalysisCount();
    List<Map<String, Object>> severityDistribution();
    List<Map<String, Object>> topSources();
    List<Map<String, Object>> latestEvents();
    List<Map<String, Object>> contextTarget(long logId);
    List<Map<String, Object>> contextBefore(@Param("batchId") long batchId, @Param("lineNo") int lineNo, @Param("limit") int limit, @Param("logId") long logId);
    List<Map<String, Object>> contextCurrent(@Param("batchId") long batchId, @Param("logId") long logId);
    List<Map<String, Object>> contextAfter(@Param("batchId") long batchId, @Param("lineNo") int lineNo, @Param("limit") int limit, @Param("logId") long logId);
}
