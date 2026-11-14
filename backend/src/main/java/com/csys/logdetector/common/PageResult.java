package com.csys.logdetector.common;

import java.util.List;

/**
 * 分页响应体：{@code {list, total, page, size}}。
 *
 * @param <T> 行数据类型
 * 封装分页列表, 总数, 页码和每页大小
 */
public class PageResult<T> {

    private List<T> list;//列表本体
    private long total;//总数
    private long page;//页码
    private long size;//每页大小

    public PageResult() {
    }

    public PageResult(List<T> list, long total, long page, long size) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> list, long total, long page, long size) {
        return new PageResult<>(list, total, page, size);
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }
}
