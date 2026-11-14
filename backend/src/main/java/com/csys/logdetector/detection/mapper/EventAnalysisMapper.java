package com.csys.logdetector.detection.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface EventAnalysisMapper {
    int insert(Map<String, Object> values);
    Long evidenceCount(@Param("eventId") long eventId, @Param("logId") long logId);
    int insertEvidence(@Param("analysisId") long analysisId, @Param("logId") long logId,
                       @Param("order") int order, @Param("stage") int stage);
    int insertCache(@Param("analysisId") Object analysisId, @Param("fingerprint") String fingerprint,
                    @Param("sourceId") Long sourceId, @Param("savedTokens") int savedTokens);
    Map<String, Object> get(@Param("analysisId") long analysisId);
    List<Map<String, Object>> latest(@Param("eventId") long eventId);
    Long count(@Param("eventId") long eventId);
    List<Map<String, Object>> list(@Param("eventId") long eventId, @Param("limit") long limit, @Param("offset") long offset);
    Long eventCount(@Param("eventId") long eventId);
}
