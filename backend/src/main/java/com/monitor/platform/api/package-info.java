/**
 * 对外 HTTP 接口模块。
 *
 * <p>Controller 只负责参数解析、调用查询/采集服务并包装响应，不直接承载业务逻辑。
 * 所有对外接口统一使用 {@code /api/v1} 前缀。</p>
 */
package com.monitor.platform.api;