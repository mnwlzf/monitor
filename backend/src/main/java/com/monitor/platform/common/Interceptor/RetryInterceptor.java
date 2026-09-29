package com.monitor.platform.common.Interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 网络层重试拦截器
 *
 * 职责边界：
 *  - ✅ 只重试「网络失败」：IO 异常、连接超时、读超时、连接拒绝、DNS 解析失败
 *  - ❌ 不重试任何 HTTP 响应（包括 4xx / 5xx），原样返回给业务层
 *  - ❌ 不感知响应体，不判断业务 code
 */
public class RetryInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RetryInterceptor.class);

    private final int maxAttempts;
    private final long backoffMillis;

    public RetryInterceptor(int maxAttempts, long backoffMillis) {
        this.maxAttempts = maxAttempts;
        this.backoffMillis = backoffMillis;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // 成功拿到响应（无论状态码是什么）→ 直接返回，不重试
                return execution.execute(request, body);

            } catch (IOException ex) {
                // 只对网络类异常重试
                if (!isRetryable(ex)) {
                    log.warn("{} {} 第 {}/{} 次非可重试异常，直接抛出: {}",
                            request.getMethod(), request.getURI(), attempt, maxAttempts, ex.getMessage());
                    throw ex;
                }

                lastException = ex;
                log.warn("{} {} 第 {}/{} 次网络异常: {}",
                        request.getMethod(), request.getURI(), attempt, maxAttempts, ex.getMessage());

                if (attempt < maxAttempts) {
                    sleepWithJitter(backoffMillis * attempt);
                }
            }
        }

        log.error("{} {} 网络重试 {} 次全部失败",
                request.getMethod(), request.getURI(), maxAttempts);
        throw lastException;
    }

    /**
     * 判断是否为可重试的网络异常
     * 只认「网络层」异常，不认 HTTP 业务异常
     */
    private boolean isRetryable(IOException ex) {
        // 常见的网络故障
        if (ex instanceof ConnectException
                || ex instanceof SocketTimeoutException
                || ex instanceof UnknownHostException) {
            return true;
        }

        // 部分底层框架会把超时包装成 IOException，用消息兜底
        String msg = ex.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            return lower.contains("timeout")
                    || lower.contains("connection reset")
                    || lower.contains("connection refused")
                    || lower.contains("no route to host")
                    || lower.contains("network is unreachable")
                    || lower.contains("temporary failure in name resolution");
        }
        return false;
    }

    /** 退避 + 抖动，避免重试风暴 */
    private void sleepWithJitter(long millis) {
        long jitter = ThreadLocalRandom.current().nextLong(millis / 2 + 1);
        try {
            Thread.sleep(millis + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("重试等待被中断");
        }
    }
}