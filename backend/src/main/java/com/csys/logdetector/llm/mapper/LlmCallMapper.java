package com.csys.logdetector.llm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.csys.logdetector.llm.entity.LlmCallLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LlmCallMapper extends BaseMapper<LlmCallLog> {
}
