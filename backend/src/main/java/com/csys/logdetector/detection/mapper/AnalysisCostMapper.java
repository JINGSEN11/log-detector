package com.csys.logdetector.detection.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Mapper
public interface AnalysisCostMapper {
    List<Long> findSources(@Param("fingerprint") String fingerprint, @Param("eventId") long eventId, @Param("cutoff") Timestamp cutoff);
    Integer savedTokens(@Param("sourceId") long sourceId);
    void recordAttempt(@Param("eventId") long eventId, @Param("model") String model, @Param("outcome") String outcome, @Param("elapsed") long elapsed, @Param("promptTokens") Integer promptTokens, @Param("completionTokens") Integer completionTokens);
    Map<String, Object> requests();
    Map<String, Object> calls();
    List<Map<String, Object>> recentCalls();
}
