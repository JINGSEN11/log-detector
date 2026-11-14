package com.csys.logdetector.log.service;

import com.csys.logdetector.common.BizException;
import com.csys.logdetector.log.dto.LogImportResult.SanitizedLogPreview;
import com.csys.logdetector.log.mapper.LogImportMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogImportPaginationTest {
    private final LogImportMapper mapper = mock(LogImportMapper.class);
    private final LogImportService service = new LogImportService(List.of(), null, null, mapper);

    @Test
    void pagesBeyondPreviewAndHandlesLastAndEmptyPages() {
        var row = new SanitizedLogPreview(101, "hash", "masked", "2024-01-01T00:00:00Z", "GET", "/", 200, "", "", "masked line");
        when(mapper.batchExists(42)).thenReturn(true);
        when(mapper.countSuccessfulEntries(42)).thenReturn(101L);
        when(mapper.pageSuccessfulEntries(42, 100, 100)).thenReturn(List.of(row));
        var page = service.getBatchEntries(42, 2, 100);
        assertEquals(101, page.total());
        assertEquals(List.of(row), page.items());
        assertTrue(service.getBatchEntries(42, 3, 100).items().isEmpty());
        assertTrue(service.getBatchEntries(42, Integer.MAX_VALUE, 1000).items().isEmpty());
        verify(mapper, never()).pageSuccessfulEntries(42, 100, 200);
    }

    @Test
    void rejectsInvalidParametersAndMissingBatch() {
        for (int[] args : List.of(new int[]{0, 1, 100}, new int[]{1, 0, 100},
                new int[]{1, 1, 0}, new int[]{1, 1, 1001})) {
            assertEquals(400, assertThrows(BizException.class,
                    () -> service.getBatchEntries(args[0], args[1], args[2])).getCode());
        }
        assertEquals(404, assertThrows(BizException.class, () -> service.getBatchEntries(1, 1, 100)).getCode());
        verify(mapper, never()).countSuccessfulEntries(anyLong());
    }
}
