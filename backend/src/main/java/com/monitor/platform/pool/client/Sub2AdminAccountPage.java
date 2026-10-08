package com.monitor.platform.pool.client;

import java.util.List;

/**
 * Sub2API 管理员号池账号列表的一页。
 *
 * <p>上游分页参数 {@code page}/{@code page_size} 生效，响应里带有
 * {@code total}/{@code pages}，用它驱动翻页比「返回条数小于页大小」更可靠。</p>
 *
 * @param items    当前页账号
 * @param page     当前页码（从 1 开始）
 * @param pageSize 每页条数
 * @param total    总条数
 * @param pages    总页数
 */
public record Sub2AdminAccountPage(
        List<Sub2AdminAccount> items,
        int page,
        int pageSize,
        int total,
        int pages
) {
}