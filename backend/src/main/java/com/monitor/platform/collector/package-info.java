/**
 * 采集调度模块。
 *
 * <p>负责按计划触发上游采集、调用对应适配器并保存原始数据。该模块不直接依赖
 * normalizer，采集完成后通过事件通知后续清洗流程。</p>
 */
package com.monitor.platform.collector;