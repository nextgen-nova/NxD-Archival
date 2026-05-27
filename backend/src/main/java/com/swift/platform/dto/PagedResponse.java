package com.swift.platform.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class PagedResponse<T> {
    private List<T>  content;
    private long     totalElements;
    private int      totalPages;
    private int      pageNumber;
    private int      pageSize;
    private boolean  first;
    private boolean  last;
    private boolean  totalExact;
    private boolean  hasNext;
    private String   nextCursor;
}
