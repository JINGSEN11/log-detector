package com.csys.logdetector.log.parser;

import java.util.Optional;

/** 单行日志格式解析器接口。 */
public interface LogParser {

    default String formatId() {
        throw new UnsupportedOperationException("Parser format ID is not defined");
    }

    Optional<ParsedLogLine> parse(String line, int lineNumber);
}
