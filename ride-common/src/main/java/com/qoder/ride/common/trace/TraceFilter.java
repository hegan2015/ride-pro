package com.qoder.ride.common.trace;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.slf4j.MDC;

import java.util.UUID;

/** 全链路 traceId：消费端透传，提供端落 MDC，供日志 pattern 输出。 */
@Activate(group = {CommonConstants.PROVIDER, CommonConstants.CONSUMER})
public class TraceFilter implements Filter {

    public static final String TRACE_ID = "traceId";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        String traceId = RpcContext.getServerAttachment().getAttachment(TRACE_ID);
        if (traceId == null || traceId.isEmpty()) {
            traceId = RpcContext.getClientAttachment().getAttachment(TRACE_ID);
        }
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(TRACE_ID, traceId);
        RpcContext.getClientAttachment().setAttachment(TRACE_ID, traceId);
        try {
            return invoker.invoke(invocation);
        } finally {
            MDC.remove(TRACE_ID);
        }
    }
}