package com.csys.logdetector.log.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScenarioReplayMapper {
    void insert(@Param("batchId") long batchId, @Param("version") int version,
                @Param("seed") long seed, @Param("baseTime") long baseTime, @Param("count") int count);
    BatchReplay find(@Param("batchId") long batchId);

    record BatchReplay(String scenario, String sha256, Integer version, Long seed, Long baseTime, Integer count) {}
}
