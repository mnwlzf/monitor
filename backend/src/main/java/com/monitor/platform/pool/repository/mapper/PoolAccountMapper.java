package com.monitor.platform.pool.repository.mapper;

import com.monitor.platform.pool.PoolAccountEntity;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 号池账号 Mapper。
 */
public interface PoolAccountMapper {

    /** 新增或更新号池账号（按平台 + 外部账号 ID 幂等）。 */
    int upsertAccount(PoolAccountEntity entity);

    /** 查询指定平台下的全部号池账号。 */
    List<PoolAccountEntity> selectByPlatform(@Param("platformId") Integer platformId);

    /** 按平台与外部账号 ID 查询号池账号。 */
    PoolAccountEntity selectByPlatformAndExternalId(@Param("platformId") Integer platformId,
                                                    @Param("externalAccountId") Long externalAccountId);

    /** 更新号池账号与本地密钥的绑定关系，绑定为空表示解除绑定。 */
    int updateBinding(@Param("platformId") Integer platformId,
                      @Param("externalAccountId") Long externalAccountId,
                      @Param("boundKeyId") Long boundKeyId);

    /** 更新号池账号的明细采集水位。 */
    int updateSampleWatermark(@Param("platformId") Integer platformId,
                              @Param("externalAccountId") Long externalAccountId,
                              @Param("lastSampleAt") OffsetDateTime lastSampleAt);

    /**
     * 设置「往回补齐」游标；传入 null 表示缺口已补齐。
     *
     * <p>触顶时把游标设为本次取到的最早一条时间，下一轮以此为查询上界继续往更早取。</p>
     */
    int updateBackfillUntil(@Param("platformId") Integer platformId,
                            @Param("externalAccountId") Long externalAccountId,
                            @Param("backfillUntil") OffsetDateTime backfillUntil);

    /** 记录号池账号最近一次采集错误；传入 null 表示清除错误。 */
    int updateSyncError(@Param("platformId") Integer platformId,
                        @Param("externalAccountId") Long externalAccountId,
                        @Param("error") String error);

    /** 按外部账号 ID 批量删除号池账号（用于清理不再监控的类型）。 */
    int deleteByExternalIds(@Param("platformId") Integer platformId,
                            @Param("externalAccountIds") Collection<Long> externalAccountIds);

    /** 查询存在号池账号的平台 ID，供定时采集遍历。 */
    List<Integer> selectDistinctPlatformIds();
}