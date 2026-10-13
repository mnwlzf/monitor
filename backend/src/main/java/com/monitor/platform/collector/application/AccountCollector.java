package com.monitor.platform.collector.application;

import com.monitor.platform.collector.repository.entity.AccountEntity;
import com.monitor.platform.collector.repository.entity.PlatformEntity;

/**
 * 单个平台类型的账号采集器。
 *
 * <p>采集编排（调度、批次、账号状态）由 {@link CollectionService} 负责；
 * 具体「怎么从上游取数、字段怎么映射」由各平台实现。新增一个上游平台时，
 * 只需要新增一个实现类，不必再改采集主流程。</p>
 */
public interface AccountCollector {

    /** 负责的平台类型，取值对应上游 {@code platform_type}（如 newapi / sub2api）。 */
    String platformType();

    /** 按范围采集一个账号。 */
    void collect(AccountEntity account, PlatformEntity platform, Long collectionRunId, CollectionScope scope);
}
