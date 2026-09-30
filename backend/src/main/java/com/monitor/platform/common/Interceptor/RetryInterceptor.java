package com.monitor.platform.common.interceptor;

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
 * 网络层重试拦截器。
 *
 * <p>职责边界：</p>
 * <ul>
 *     <li>只重试网络失败：IO 异常、连接超时、读超时、连接拒绝、DNS 解析失败；</li>
 *     <li>不重试任何 HTTP 响应，包括 4xx 和 5xx，响应原样交给业务层判断；</li>
 *     <li>不解析响应体，也不判断上游业务状态码。</li>
 * </ul>
 */
public class RetryInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RetryInterceptor.class);

    private final int maxAttempts;
    private final long backoffMillis;

    /**
     * @param maxAttempts   最大请求次数，包含第一次请求
     * @param backoffMillis 基础退避时间，实际等待时间会叠加随机抖动
     */
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
                // 成功拿到响应后直接返回，无论 HTTP 状态码是什么，均不在网络层重试。
                return execution.execute(request, body);
            } catch (IOException ex) {
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
     * 判断异常是否属于可以安全重试的网络故障。
     */
    private boolean isRetryable(IOException ex) {
        if (ex instanceof ConnectException
                || ex instanceof SocketTimeoutException
                || ex instanceof UnknownHostException) {
            return true;
        }

        // 部分底层框架会把超时或连接中断包装成普通 IOException，使用消息兜底识别。
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

    /**
     * 线性退避叠加随机抖动，避免多个请求在同一时刻集中重试。
     */
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