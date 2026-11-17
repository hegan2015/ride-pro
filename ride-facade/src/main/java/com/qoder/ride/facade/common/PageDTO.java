package com.qoder.ride.facade.common;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 分页结果。page 从 1 起。 */
@Data
public class PageDTO<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<T> items;
    private long total;
    private int page;
    private int size;

    public static <T> PageDTO<T> of(List<T> items, long total, int page, int size) {
        PageDTO<T> p = new PageDTO<>();
        p.items = items;
        p.total = total;
        p.page = page;
        p.size = size;
        return p;
    }
}