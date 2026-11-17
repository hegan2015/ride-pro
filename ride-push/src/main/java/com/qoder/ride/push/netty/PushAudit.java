package com.qoder.ride.push.netty;

import com.qoder.ride.facade.push.PushEventDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 推送事件审计：内存环形缓冲（最新在前），供管理端排查投递情况，重启即清空。 */
@Component
public class PushAudit {

    private static final int CAPACITY = 500;

    private final Deque<PushEventDTO> events = new ArrayDeque<>();

    public synchronized void record(String key, String event, String orderId, boolean delivered) {
        PushEventDTO dto = new PushEventDTO();
        dto.setKey(key);
        dto.setEvent(event);
        dto.setOrderId(orderId);
        dto.setDelivered(delivered);
        dto.setTs(System.currentTimeMillis());
        events.addFirst(dto);
        while (events.size() > CAPACITY) {
            events.removeLast();
        }
    }

    public synchronized List<PushEventDTO> recent(int limit) {
        int n = Math.max(1, Math.min(limit, CAPACITY));
        List<PushEventDTO> result = new ArrayList<>(n);
        for (PushEventDTO dto : events) {
            if (result.size() >= n) {
                break;
            }
            result.add(dto);
        }
        return result;
    }
}