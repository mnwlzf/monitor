<template>
  <section class="admin-page">
    <div class="admin-page-heading">
      <div>
        <p class="admin-eyebrow">SYSTEM SETTINGS</p>
        <h2>系统设置</h2>
        <p>按功能分组配置邮件服务、通知订阅、QQ 机器人与权限身份。</p>
      </div>
    </div>

    <el-tabs v-model="activeSettingsTab" class="settings-tabs">
      <el-tab-pane label="邮件服务" name="mail" lazy>
    <el-card shadow="never" class="admin-card" v-loading="loading">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>SMTP 设置</h3>
            <p>配置用于发送通知邮件的邮件服务</p>
          </div>
          <div class="settings-header-actions">
            <el-button :loading="testing" :disabled="canWrite === false" @click="testConnection">测试连接</el-button>
            <el-button v-if="canWrite !== false" type="primary" :loading="saving" @click="save">保存设置</el-button>
          </div>
        </div>
      </template>

      <el-form label-position="top">
        <div class="admin-form-grid">
          <el-form-item label="SMTP 主机">
            <el-input v-model="form.host" placeholder="smtp.qq.com" :disabled="canWrite === false" />
          </el-form-item>
          <el-form-item label="SMTP 端口">
            <el-input-number
              v-model="form.port"
              :min="1"
              :max="65535"
              controls-position="right"
              style="width: 100%"
              :disabled="canWrite === false"
            />
          </el-form-item>
          <el-form-item label="SMTP 用户名">
            <el-input v-model="form.username" placeholder="noreply@example.com" :disabled="canWrite === false" />
          </el-form-item>
          <el-form-item label="SMTP 密码">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              :placeholder="passwordPlaceholder"
              :disabled="canWrite === false"
            />
            <small class="admin-form-hint">{{ passwordHint }}</small>
          </el-form-item>
          <el-form-item label="发件人邮箱">
            <el-input v-model="form.from" placeholder="noreply@example.com" :disabled="canWrite === false" />
          </el-form-item>
          <el-form-item label="发件人名称">
            <el-input v-model="form.fromName" placeholder="Monitor" :disabled="canWrite === false" />
          </el-form-item>
          <div class="admin-form-full settings-toggle-row">
            <div>
              <div class="settings-toggle-title">使用 TLS</div>
              <small class="admin-form-hint">为 SMTP 连接启用隐式 TLS（通常为 465 端口）；关闭后按机会式 STARTTLS（587/25）连接。</small>
            </div>
            <el-switch v-model="form.useTls" :disabled="canWrite === false" />
          </div>
          <div class="admin-form-full settings-toggle-row">
            <div>
              <div class="settings-toggle-title">启用邮件通知</div>
              <small class="admin-form-hint">关闭后不会发送任何通知邮件，但仍可在本页测试配置。</small>
            </div>
            <el-switch v-model="form.enabled" :disabled="canWrite === false" />
          </div>
        </div>
      </el-form>
    </el-card>

    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>发送测试邮件</h3>
            <p>发送测试邮件以验证 SMTP 配置</p>
          </div>
        </div>
      </template>

      <div class="settings-test-email">
        <el-input
          v-model="testRecipient"
          placeholder="test@example.com"
          :disabled="canWrite === false"
          @keyup.enter="sendTestEmail"
        />
        <el-button type="primary" plain :loading="sending" :disabled="canWrite === false" @click="sendTestEmail">
          发送测试邮件
        </el-button>
      </div>
      <small class="admin-form-hint">使用上方当前填写的配置（未保存也可）发送一封测试邮件。</small>
    </el-card>
      </el-tab-pane>

      <el-tab-pane label="通知订阅" name="notifications" lazy>
    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>余额不足提醒</h3>
            <p>余额采集完成后，平台账号余额合计低于阈值时发送邮件与 QQ 消息提醒</p>
          </div>
          <el-button v-if="canWrite !== false" type="primary" :loading="savingNotification" @click="saveNotification">保存</el-button>
        </div>
      </template>

      <el-form label-position="top">
        <div class="admin-form-grid">
          <el-form-item label="启用余额提醒">
            <el-switch v-model="notification.balanceAlertEnabled" :disabled="canWrite === false" />
            <small class="admin-form-hint">关闭后余额提醒的邮件与 QQ 消息都不再发送。</small>
          </el-form-item>
          <el-form-item label="余额阈值（USD）">
            <el-input-number
              v-model="notification.balanceThreshold"
              :min="0"
              :step="1"
              :precision="2"
              controls-position="right"
              style="width: 100%"
              :disabled="canWrite === false"
            />
            <small class="admin-form-hint">平台账号余额合计低于该值时提醒，默认 5。</small>
          </el-form-item>
          <el-form-item label="提醒间隔（分钟）">
            <el-input-number
              v-model="notification.alertIntervalMinutes"
              :min="1"
              :step="60"
              controls-position="right"
              style="width: 100%"
              :disabled="canWrite === false"
            />
            <small class="admin-form-hint">余额持续低于阈值时，每隔该时长重复提醒一次，默认 360 分钟（6 小时）。</small>
          </el-form-item>
        </div>
      </el-form>

      <el-divider content-position="left">该事件的邮件收件人</el-divider>
      <RecipientEditor scene="BALANCE_ALERT" :can-write="canWrite" />

      <el-divider content-position="left">该事件的 QQ 消息接收人</el-divider>
      <small class="admin-form-hint">
        余额提醒除邮件外，还会通过 QQ 机器人推送到这里配置的群 / 私聊；只配 QQ 不配邮件也能收到提醒。
        OneBot 没接通（monitor.bot.enabled / api-base-url / webhook-token）时只发邮件。
      </small>
      <QqRecipientEditor scene="BALANCE_ALERT" :can-write="canWrite" />
    </el-card>

    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>每日余额消耗报表</h3>
            <p>每天凌晨发送前一天各平台、各账号的余额消耗报表</p>
          </div>
        </div>
      </template>

      <small class="admin-form-hint">发送时间与启停在「定时任务」页配置（任务：每日余额消耗报表）。</small>

      <el-divider content-position="left">该事件的收件人</el-divider>
      <RecipientEditor scene="DAILY_REPORT" :can-write="canWrite" />
    </el-card>

    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>密钥变更提醒</h3>
            <p>已绑定到本平台号池账号的密钥发生变更时发送邮件提醒</p>
          </div>
        </div>
      </template>

      <small class="admin-form-hint">由「API Key 采集」任务在采集完成后触发，只提醒本轮新产生的变更。</small>

      <el-divider content-position="left">该事件的收件人</el-divider>
      <RecipientEditor scene="API_KEY_CHANGE" :can-write="canWrite" />
    </el-card>

    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>渠道倍率变化提醒（实时）</h3>
            <p>正在使用的渠道倍率发生变化时立刻发邮件 —— 上调会直接推高成本，不能等</p>
          </div>
        </div>
      </template>

      <small class="admin-form-hint">
        由「分组·渠道倍率采集」任务在采集完成后触发，只提醒本轮新产生的变化，且只覆盖<strong>正在使用</strong>的渠道。
        邮件正文由大模型读取原始变更后总结；模型不可用时自动退回原始明细。
      </small>

      <el-divider content-position="left">该事件的收件人</el-divider>
      <RecipientEditor scene="CHANNEL_ALERT" :can-write="canWrite" />
    </el-card>

    <el-card shadow="never" class="admin-card">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>渠道变更汇总（每日）</h3>
            <p>非在用渠道的变更每天汇总一封，避免把实时告警淹掉</p>
          </div>
        </div>
      </template>

      <small class="admin-form-hint">
        汇总最近 24 小时<strong>非在用</strong>渠道的变更。发送时间与启停在「定时任务」页配置（任务：渠道变更每日汇总）。
      </small>

      <el-divider content-position="left">该事件的收件人</el-divider>
      <RecipientEditor scene="CHANNEL_DIGEST" :can-write="canWrite" />
    </el-card>
      </el-tab-pane>

      <el-tab-pane label="抽奖活动" name="lottery" lazy>
    <el-card shadow="never" class="admin-card" v-loading="lotteryLoading">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>每日消费抽奖</h3>
            <p>按前一个完整自然日的实际余额消费核算资格；修改时仅实时预览，点击保存后立即生效</p>
          </div>
          <div class="settings-header-actions">
            <span class="admin-form-hint">{{ lotterySaving ? '正在保存…' : '保存后立即生效' }}</span>
            <el-button v-if="canWrite !== false" type="primary" :loading="lotterySaving" @click="saveLotterySettingsForm">
              保存设置
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="lottery-alert"
        title="资格消费 = 抽奖日的前一天 00:00–24:00（Asia/Shanghai）的 actual_cost；不是从当前时间往前倒推 24 小时。"
      />
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="lottery-alert"
        title="邮箱绑定固定规则：验证码 5 分钟有效；同一 QQ 60 秒内不可重复发送；同一 QQ、同一邮箱每小时各最多 5 次；验证码最多输错 5 次。"
      />
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        class="lottery-alert"
        title="抽奖只在「指定群（平台功能 / OpenAI 状态）」或指定群临时会话中触发；群内任何人都可以发起，未绑定邮箱时会提示先绑定。"
      />

      <div class="lottery-settings-layout">
        <div class="lottery-settings-form">
      <el-form label-width="180px" :disabled="!canWrite">
        <el-form-item label="启用抽奖">
          <el-switch v-model="lotteryForm.enabled" />
          <span class="admin-form-hint" style="margin-left: 10px">关闭后 /抽奖 会提示活动未开启</span>
        </el-form-item>
        <el-form-item label="前一日最低消费">
          <el-input-number v-model="lotteryForm.minUsageDaily" :min="0" :max="1000000" :step="1" :precision="2" />
          <span class="admin-form-hint" style="margin-left: 10px">
            前一天实际扣余额消费达到该值才有资格；提高门槛会减少参与人数和总成本
          </span>
        </el-form-item>
        <el-form-item label="中奖比例">
          <el-input-number
            v-model="lotteryForm.payoutRatioPercent"
            :min="0.01"
            :max="100"
            :step="0.1"
            :precision="2"
            @input="recalculateLotteryPreviewSoon"
            @change="recalculateLotteryPreview"
          />
          <span class="admin-form-hint" style="margin-left: 10px">
            基础奖励 = 前一日消费 × 中奖比例；该比例不等同于最终实际成本率
          </span>
        </el-form-item>
        <el-form-item label="随机系数下限">
          <el-input-number
            v-model="lotteryForm.multiplierMin"
            :min="0.01"
            :max="100"
            :step="0.1"
            :precision="2"
            @input="recalculateLotteryPreviewSoon"
            @change="recalculateLotteryPreview"
          />
          <span class="admin-form-hint" style="margin-left: 10px">与上限共同决定奖励波动和长期平均成本</span>
        </el-form-item>
        <el-form-item label="随机系数上限">
          <el-input-number
            v-model="lotteryForm.multiplierMax"
            :min="0.01"
            :max="100"
            :step="0.1"
            :precision="2"
            @input="recalculateLotteryPreviewSoon"
            @change="recalculateLotteryPreview"
          />
          <span class="admin-form-hint" style="margin-left: 10px">上限不能小于下限</span>
        </el-form-item>
        <el-form-item label="单人单次最高奖励">
          <el-input-number
            v-model="lotteryForm.maxReward"
            :min="0.01"
            :max="1000000"
            :step="0.5"
            :precision="2"
            @input="recalculateLotteryPreviewSoon"
            @change="recalculateLotteryPreview"
          />
          <span class="admin-form-hint" style="margin-left: 10px">只限制单个用户一次奖励，不是每日总支出上限</span>
        </el-form-item>
        <el-form-item label="每日总奖励预算">
          <el-input-number
            v-model="lotteryForm.dailyBudget"
            :min="0.01"
            :max="10000000"
            :step="10"
            :precision="2"
            @input="recalculateLotteryPreviewSoon"
            @change="recalculateLotteryPreview"
          />
          <span class="admin-form-hint" style="margin-left: 10px">所有用户当天累计发放的硬上限，达到后停止发奖</span>
        </el-form-item>
        <el-form-item label="每人每天抽奖次数">
          <el-tag type="info" effect="plain">固定 1 次</el-tag>
          <span class="admin-form-hint" style="margin-left: 10px">当天已成功抽奖或未中奖都会消耗当天机会</span>
        </el-form-item>
        <el-form-item>
          <span v-if="lotteryUpdatedAt" class="admin-form-hint">上次保存：{{ lotteryUpdatedAt }}</span>
        </el-form-item>
      </el-form>
        </div>

        <div class="lottery-settings-preview">
      <div class="lottery-preview-heading">
        <strong>成本与奖励预览</strong>
        <span>修改左侧参数后立即重新计算</span>
      </div>
      <div class="lottery-preview">
        <div class="lottery-preview-item">
          <span>平均随机系数</span>
          <strong>{{ lotteryAverageMultiplier.toFixed(2) }}</strong>
          <small>=(下限 + 上限) / 2</small>
        </div>
        <div class="lottery-preview-item">
          <span>综合平均奖励率</span>
          <strong>{{ lotteryEffectiveRatio.toFixed(2) }}%</strong>
          <small>中奖比例 × 平均随机系数</small>
        </div>
        <div class="lottery-preview-item">
          <span>单人单次封顶</span>
          <strong>${{ Number(lotteryForm.maxReward || 0).toFixed(2) }}</strong>
          <small>超过部分不发放</small>
        </div>
        <div class="lottery-preview-item">
          <span>每日总预算</span>
          <strong>${{ Number(lotteryForm.dailyBudget || 0).toFixed(2) }}</strong>
          <small>全平台当天累计上限</small>
        </div>
      </div>

      <div class="lottery-ladder-title">
        <strong>奖励阶梯实时预览</strong>
        <span>前一日消费 $10–$100，每 $1 一档，实时跟随上方参数</span>
      </div>
      <el-table :data="lotteryPreviewRows" size="small" class="lottery-preview-table" max-height="380">
        <el-table-column prop="label" label="前一日实际消费" min-width="180" />
        <el-table-column label="最少可获得" width="160" align="right">
          <template #default="{ row }">${{ Number(row.minReward).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="最多可获得" width="160" align="right">
          <template #default="{ row }">${{ Number(row.maxReward).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="封顶情况" min-width="180">
          <template #default="{ row }">
            <el-tag v-if="row.capped" type="warning" size="small" effect="plain">已触发单次奖励封顶</el-tag>
            <span v-else class="muted">未触发封顶</span>
          </template>
        </el-table-column>
      </el-table>
      <small class="admin-form-hint">
        随机系数围绕 1.0 对称时，长期平均成本基本等于前一日消费 × 中奖比例；最低奖励已取消，金额不足 $0.01 时按 $0.00 处理。
      </small>
        </div>
      </div>

      <el-divider content-position="left">最近抽奖记录</el-divider>
      <el-table :data="lotteryDraws" size="small" class="lottery-draw-table">
        <template #empty>还没有抽奖记录</template>
        <el-table-column prop="email" label="用户邮箱" min-width="220" show-overflow-tooltip />
        <el-table-column prop="drawDate" label="抽奖日期" width="120" />
        <el-table-column label="前一日消费" width="130" align="right">
          <template #default="{ row }">${{ Number(row.eligibleUsage).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="奖励" width="100" align="right">
          <template #default="{ row }">${{ Number(row.rewardAmount).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="lotteryStatusType(row.status)" size="small" effect="plain">
              {{ lotteryStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatIdentityTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="failureReason" label="失败原因" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.failureReason || '-' }}</template>
        </el-table-column>
      </el-table>
    </el-card>
      </el-tab-pane>

      <el-tab-pane label="机器人配置" name="bot" lazy>
    <el-card shadow="never" class="admin-card" v-loading="botLoading">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>QQ 机器人</h3>
            <p>白名单与行为参数保存在数据库里，改完立即生效，不需要重建容器</p>
          </div>
        </div>
      </template>

      <el-form label-width="150px" :disabled="!canWrite">
        <el-form-item label="启用">
          <el-switch v-model="botForm.enabled" />
          <span class="admin-form-hint" style="margin-left: 10px">
            关闭后机器人收到消息也不响应；服务端总开关仍是环境变量 MONITOR_BOT_ENABLED
          </span>
        </el-form-item>

        <el-form-item label="允许的群号">
          <el-select
            v-model="botForm.allowedGroups"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="输入群号后回车；留空表示不限制"
            style="width: 100%"
          >
            <el-option v-for="item in botForm.allowedGroups" :key="item" :label="item" :value="item" />
          </el-select>
          <small class="admin-form-hint">只有这些群里 @机器人 才会响应</small>
        </el-form-item>

        <el-form-item label="指定群（平台功能 / OpenAI 状态）">
          <el-select
            v-model="botForm.platformGroups"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="只有这些群才允许平台功能和 OpenAI 状态查询；留空表示都不给"
            style="width: 100%"
          >
            <el-option v-for="item in botForm.platformGroups" :key="item" :label="item" :value="item" />
          </el-select>
          <small class="admin-form-hint">
            必须是「允许的群号」的子集。群聊和群临时会话可正常查询，但账号名称、上游平台名称/地址和邮箱会自动脱敏；OpenAI 异常告警也发送到这些群。留空 = 任何群都不给平台功能
          </small>
        </el-form-item>

        <el-form-item label="允许的私聊 QQ">
          <el-select
            v-model="botForm.allowedUsers"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="输入 QQ 号后回车；留空表示不限制"
            style="width: 100%"
          >
            <el-option v-for="item in botForm.allowedUsers" :key="item" :label="item" :value="item" />
          </el-select>
          <small class="admin-form-hint">只有这些 QQ 私聊机器人才会响应</small>
        </el-form-item>

        <el-form-item label="群里需 @机器人">
          <el-switch v-model="botForm.requireMention" />
          <span class="admin-form-hint" style="margin-left: 10px">关掉后群里任意消息都会触发，容易刷屏</span>
        </el-form-item>

        <el-form-item label="命令前缀">
          <el-input v-model="botForm.commandPrefix" style="width: 120px" maxlength="8" />
          <span class="admin-form-hint" style="margin-left: 10px">例如 / 表示 /help、/号池</span>
        </el-form-item>

        <el-form-item label="回复最大长度">
          <el-input-number v-model="botForm.maxReplyLength" :min="50" :max="4000" :step="50" />
          <span class="admin-form-hint" style="margin-left: 10px">超长会被截断（QQ 对消息长度有限制）</span>
        </el-form-item>

        <el-form-item label="会话记忆条数">
          <el-input-number v-model="botForm.memoryWindow" :min="2" :max="50" />
          <span class="admin-form-hint" style="margin-left: 10px">保留最近几条对话，用于「那 30 天呢？」这类追问</span>
        </el-form-item>

        <el-divider content-position="left">语音消息与语音回复</el-divider>

        <el-form-item label="MOSS 默认音色">
          <el-select v-model="botForm.defaultVoice" style="width: 360px" placeholder="请选择默认音色">
            <el-option label="alex（沉稳男声）" value="fnlp/MOSS-TTSD-v0.5:alex" />
            <el-option label="benjamin（低沉男声）" value="fnlp/MOSS-TTSD-v0.5:benjamin" />
            <el-option label="charles（磁性男声）" value="fnlp/MOSS-TTSD-v0.5:charles" />
            <el-option label="david（欢快男声）" value="fnlp/MOSS-TTSD-v0.5:david" />
            <el-option label="anna（沉稳女声）" value="fnlp/MOSS-TTSD-v0.5:anna" />
            <el-option label="bella（激情女声）" value="fnlp/MOSS-TTSD-v0.5:bella" />
            <el-option label="claire（温柔女声）" value="fnlp/MOSS-TTSD-v0.5:claire" />
            <el-option label="diana（欢快女声）" value="fnlp/MOSS-TTSD-v0.5:diana" />
          </el-select>
          <small class="admin-form-hint">仅支持以上 8 个系统音色；用户说「用 claire 音色语音回复」时会临时覆盖，未知音色自动回退到 alex</small>
        </el-form-item>

        <el-form-item label="默认语音风格">
          <el-select v-model="botForm.defaultVoiceStyle" filterable allow-create default-first-option style="width: 360px" placeholder="例如：自然、友好、简洁">
            <el-option label="自然、友好、简洁" value="自然、友好、简洁" />
            <el-option label="温柔亲切" value="温柔亲切" />
            <el-option label="沉稳专业" value="沉稳专业" />
            <el-option label="活泼轻松" value="活泼轻松" />
            <el-option label="播音腔" value="播音腔" />
          </el-select>
          <small class="admin-form-hint">用户明确要求语音回复时生效；这个风格用于组织回复措辞，实际音色由上面的 MOSS 音色决定</small>
        </el-form-item>

        <el-form-item label="普通人可用联网搜索">
          <el-switch v-model="botForm.webSearchForNonAdmin" />
          <span class="admin-form-hint" style="margin-left: 10px">
            管理员始终可用（包括不在「指定群」里的群聊）；这个开关只控制<strong>平台普通用户 / 陌生人</strong>。
            不受「指定群」限制 —— 群里、私聊都行，只受上面的「允许的群号 / 私聊 QQ」白名单约束。
            需要服务端已配置 DeepSeek 官方 Key（MONITOR_WEB_SEARCH_API_KEY）；保存后立即生效，不用重启
          </span>
        </el-form-item>

        <el-divider content-position="left">Sub2API 错误主动告警</el-divider>

        <el-form-item label="启用错误告警">
          <el-switch v-model="botForm.errorAlertEnabled" />
          <span class="admin-form-hint" style="margin-left: 10px">
            当前窗口的错误、429/529 或入口拒绝相对上一周期突增时，向下方接收人发送 QQ 消息
          </span>
        </el-form-item>

        <el-form-item label="对比窗口（分钟）">
          <el-input-number v-model="botForm.errorAlertWindowMinutes" :min="1" :max="1440" :step="5" />
          <span class="admin-form-hint" style="margin-left: 10px">当前窗口与上一周期等长，默认 15 分钟</span>
        </el-form-item>

        <el-form-item label="最小触发数量">
          <el-input-number v-model="botForm.errorAlertMinCount" :min="1" :max="100000" :step="1" />
          <span class="admin-form-hint" style="margin-left: 10px">当前窗口至少达到这个数量才可能触发，默认 5</span>
        </el-form-item>

        <el-form-item label="突增倍数">
          <el-input-number v-model="botForm.errorAlertSpikeRatio" :min="1" :max="100" :step="0.1" :precision="2" />
          <span class="admin-form-hint" style="margin-left: 10px">当前数量达到上一周期的多少倍时触发，默认 1.5 倍</span>
        </el-form-item>

        <el-form-item label="告警冷却（分钟）">
          <el-input-number v-model="botForm.errorAlertCooldownMinutes" :min="1" :max="1440" :step="5" />
          <span class="admin-form-hint" style="margin-left: 10px">同一告警在冷却期内不重复发送，默认 30 分钟</span>
        </el-form-item>

        <el-form-item label="告警接收人">
          <div style="width: 100%">
            <QqRecipientEditor scene="ERROR_ALERT" :can-write="canWrite" />
            <small class="admin-form-hint">接收人可以同时配置群和私聊；接收人增删立即生效，开关和阈值点下方「保存」后生效。</small>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="botSaving" @click="saveBotSettingsForm">保存</el-button>
          <span v-if="botUpdatedAt" class="admin-form-hint" style="margin-left: 12px">上次保存：{{ botUpdatedAt }}</span>
        </el-form-item>
      </el-form>
    </el-card>
      </el-tab-pane>

      <el-tab-pane label="违禁词" name="forbidden" lazy>
    <el-card shadow="never" class="admin-card" v-loading="forbiddenLoading">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>违禁词管理</h3>
            <p>消息原文、引用内容或语音转写包含任一违禁词时直接拒绝回答；普通用户全渠道生效，不受白名单或 @ 限制，管理员豁免</p>
          </div>
        </div>
      </template>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="forbidden-alert"
        title="管理员可在私聊或任意群维护：说「查看违禁词」「添加违禁词 测试词」「把测试词从违禁词中删除」，或使用对应的 / 命令"
      />

      <div class="forbidden-add-row">
        <el-input
          v-model="newForbiddenWord"
          maxlength="100"
          show-word-limit
          placeholder="输入违禁词或短语"
          :disabled="!canWrite"
          @keyup.enter="addForbiddenWord"
        />
        <el-button type="primary" :loading="forbiddenAdding" :disabled="!canWrite" @click="addForbiddenWord">
          添加
        </el-button>
      </div>

      <el-table :data="forbiddenWords" size="small" class="forbidden-table">
        <template #empty>还没有违禁词，机器人不会拦截任何内容</template>
        <el-table-column prop="word" label="违禁词" min-width="260" />
        <el-table-column label="添加时间" width="190">
          <template #default="{ row }">{{ formatIdentityTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" :disabled="!canWrite" @click="removeForbiddenWord(row as BotForbiddenWord)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
      </el-tab-pane>

      <el-tab-pane label="身份与管理员" name="identity" lazy>
    <el-card shadow="never" class="admin-card" v-loading="identityLoading">
      <template #header>
        <div class="admin-card-header">
          <div>
            <h3>身份识别与管理员</h3>
            <p>
              QQ 消息按邮箱识别身份：平台用户以「监控助手」回答，其他人只能是普通聊天，接触不到任何平台信息
            </p>
          </div>
          <el-button :loading="identitySyncing" :disabled="!canWrite" @click="syncIdentity">
            立即同步用户
          </el-button>
        </div>
      </template>

      <el-descriptions :column="2" border size="small" class="identity-summary">
        <el-descriptions-item label="Sub2API 用户">
          {{ identity.sub2Api.userCount }} 人
        </el-descriptions-item>
        <el-descriptions-item label="Sub2API 管理员">
          {{ identity.sub2Api.adminCount }} 人
        </el-descriptions-item>
        <el-descriptions-item label="最近同步">
          {{ identitySyncTime }}
        </el-descriptions-item>
        <el-descriptions-item label="只读库">
          <el-tag :type="identity.sub2Api.available ? 'success' : 'info'" size="small" effect="plain">
            {{ identity.sub2Api.available ? '已配置' : '未配置' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <el-alert
        v-if="!identity.sub2Api.available"
        type="warning"
        :closable="false"
        show-icon
        class="identity-alert"
        title="未配置 Sub2API 只读库"
        description="需要在 .env 中配置 SUB2API_DB_URL / SUB2API_DB_USERNAME / SUB2API_DB_PASSWORD，并给只读账号授予 public.users 的 SELECT 权限。"
      />

      <div class="identity-section-title">
        自定义管理员
        <small>Sub2API 只允许一个管理员，这里可以再加人；与 Sub2API 的管理员取并集</small>
      </div>

      <div class="identity-add-row">
        <el-input
          v-model="newAdminEmail"
          placeholder="管理员邮箱，例如 123456@qq.com"
          style="width: 300px"
          :disabled="!canWrite"
          @keyup.enter="addAdmin"
        />
        <el-input
          v-model="newAdminRemark"
          placeholder="备注（可选）"
          style="width: 200px"
          :disabled="!canWrite"
          @keyup.enter="addAdmin"
        />
        <el-button type="primary" :loading="adminAdding" :disabled="!canWrite" @click="addAdmin">
          添加
        </el-button>
      </div>

      <el-table :data="identity.customAdmins" size="small" class="identity-table">
        <template #empty>还没有自定义管理员，Sub2API 自带的管理员仍然生效</template>
        <el-table-column prop="email" label="邮箱" min-width="220" />
        <el-table-column prop="remark" label="备注" min-width="140">
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="添加时间" width="180">
          <template #default="{ row }">{{ formatIdentityTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link type="danger" :disabled="!canWrite" @click="removeAdmin(row as BotAdmin)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
      </el-tab-pane>
    </el-tabs>

  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import RecipientEditor from '../components/RecipientEditor.vue'
import QqRecipientEditor from '../components/QqRecipientEditor.vue'
import {
  addBotAdmin,
  addBotForbiddenWord,
  deleteBotAdmin,
  deleteBotForbiddenWord,
  getBotLotterySettings,
  getBotIdentity,
  getBotSettings,
  getMailSettings,
  getNotificationSettings,
  listBotForbiddenWords,
  listBotLotteryDraws,
  saveBotLotterySettings,
  saveBotSettings,
  saveMailSettings,
  saveNotificationSettings,
  sendTestMail,
  syncBotIdentity,
  testMailConnection,
  type BotLotterySettingsInput,
  type BotSettingsInput,
  type MailSettingsInput,
  type NotificationSettingsInput,
} from '../api/settings'
import type { BotAdmin, BotForbiddenWord, BotIdentityOverview, BotLotteryDraw, BotLotterySettings, BotSettings } from '../types'

const props = defineProps<{ canWrite?: boolean }>()

const activeSettingsTab = ref('mail')
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const sending = ref(false)
const testRecipient = ref('')
const passwordConfigured = ref(false)

const form = reactive<MailSettingsInput>({
  enabled: false,
  host: '',
  port: 587,
  username: '',
  password: '',
  from: '',
  fromName: 'Monitor',
  useTls: false,
})

const passwordPlaceholder = computed(() => (passwordConfigured.value ? '••••••••' : '请输入 SMTP 密码或授权码'))
const passwordHint = computed(() =>
  passwordConfigured.value ? '密码已配置，留空以保留当前值。' : '部分邮箱需使用授权码而非登录密码。',
)

function payload(): MailSettingsInput {
  return { ...form }
}

async function load() {
  loading.value = true
  try {
    const settings = await getMailSettings()
    passwordConfigured.value = settings.passwordConfigured
    Object.assign(form, {
      enabled: settings.enabled,
      host: settings.host ?? '',
      port: settings.port ?? 587,
      username: settings.username ?? '',
      password: '',
      from: settings.from ?? '',
      fromName: settings.fromName ?? 'Monitor',
      useTls: settings.useTls,
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载 SMTP 设置失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const settings = await saveMailSettings(payload())
    passwordConfigured.value = settings.passwordConfigured
    form.password = ''
    ElMessage.success('SMTP 设置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function testConnection() {
  testing.value = true
  try {
    await testMailConnection(payload())
    ElMessage.success('SMTP 连接成功')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'SMTP 连接失败')
  } finally {
    testing.value = false
  }
}

async function sendTestEmail() {
  if (!testRecipient.value) {
    ElMessage.warning('请填写收件人邮箱')
    return
  }
  sending.value = true
  try {
    await sendTestMail(testRecipient.value, payload())
    ElMessage.success('测试邮件已发送')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '测试邮件发送失败')
  } finally {
    sending.value = false
  }
}

const notification = reactive<NotificationSettingsInput>({
  balanceAlertEnabled: true,
  balanceThreshold: 5,
  alertIntervalMinutes: 360,
})
const savingNotification = ref(false)

async function loadNotification() {
  try {
    const settings = await getNotificationSettings()
    Object.assign(notification, {
      balanceAlertEnabled: settings.balanceAlertEnabled,
      balanceThreshold: settings.balanceThreshold ?? 5,
      alertIntervalMinutes: settings.alertIntervalMinutes ?? 360,
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载通知设置失败')
  }
}

async function saveNotification() {
  savingNotification.value = true
  try {
    const settings = await saveNotificationSettings({ ...notification })
    Object.assign(notification, {
      balanceAlertEnabled: settings.balanceAlertEnabled,
      balanceThreshold: settings.balanceThreshold ?? 5,
      alertIntervalMinutes: settings.alertIntervalMinutes ?? 360,
    })
    ElMessage.success('余额提醒设置已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    savingNotification.value = false
  }
}

const botLoading = ref(false)
const botSaving = ref(false)
const botUpdatedAt = ref('')
const forbiddenLoading = ref(false)
const forbiddenAdding = ref(false)
const forbiddenWords = ref<BotForbiddenWord[]>([])
const newForbiddenWord = ref('')
const lotteryLoading = ref(false)
const lotterySaving = ref(false)
const lotteryUpdatedAt = ref('')
const lotteryDraws = ref<BotLotteryDraw[]>([])
const lotteryForm = reactive<BotLotterySettingsInput>({
  enabled: false,
  minUsageDaily: 10,
  payoutRatioPercent: 3,
  multiplierMin: 0.8,
  multiplierMax: 1.2,
  maxReward: 5,
  dailyBudget: 50,
})
const botForm = reactive<BotSettingsInput>({
  enabled: true,
  allowedGroups: [],
  allowedUsers: [],
  platformGroups: [],
  requireMention: true,
  commandPrefix: '/',
  maxReplyLength: 900,
  memoryWindow: 10,
  webSearchForNonAdmin: false,
  errorAlertEnabled: false,
  errorAlertWindowMinutes: 15,
  errorAlertMinCount: 5,
  errorAlertSpikeRatio: 1.5,
  errorAlertCooldownMinutes: 30,
  defaultVoice: 'fnlp/MOSS-TTSD-v0.5:alex',
  defaultVoiceStyle: '自然、友好、简洁',
})

function applyBotSettings(settings: BotSettings) {
  Object.assign(botForm, {
    enabled: settings.enabled,
    allowedGroups: [...(settings.allowedGroups ?? [])],
    allowedUsers: [...(settings.allowedUsers ?? [])],
    platformGroups: [...(settings.platformGroups ?? [])],
    requireMention: settings.requireMention,
    commandPrefix: settings.commandPrefix || '/',
    maxReplyLength: settings.maxReplyLength || 900,
    memoryWindow: settings.memoryWindow || 10,
    webSearchForNonAdmin: settings.webSearchForNonAdmin === true,
    errorAlertEnabled: settings.errorAlertEnabled === true,
    errorAlertWindowMinutes: settings.errorAlertWindowMinutes || 15,
    errorAlertMinCount: settings.errorAlertMinCount || 5,
    errorAlertSpikeRatio: settings.errorAlertSpikeRatio ?? 1.5,
    errorAlertCooldownMinutes: settings.errorAlertCooldownMinutes || 30,
    defaultVoice: settings.defaultVoice || 'fnlp/MOSS-TTSD-v0.5:alex',
    defaultVoiceStyle: settings.defaultVoiceStyle || '自然、友好、简洁',
  })
  botUpdatedAt.value = settings.updatedAt ? new Date(settings.updatedAt).toLocaleString('zh-CN') : ''
}

async function loadBot() {
  botLoading.value = true
  try {
    applyBotSettings(await getBotSettings())
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载机器人设置失败')
  } finally {
    botLoading.value = false
  }
}

/** 「指定群」必须是「启用群」的子集；启用群留空（不限制）时无需校验。 */
function validateBotPlatformGroups(): boolean {
  const allowed = botForm.allowedGroups ?? []
  if (allowed.length === 0) return true
  const outside = (botForm.platformGroups ?? []).filter((g) => !allowed.includes(g))
  if (outside.length > 0) {
    ElMessage.error(`指定群必须是启用群的子集，以下群号不在启用群里：${outside.join('、')}`)
    return false
  }
  return true
}

async function saveBotSettingsForm() {
  if (!validateBotPlatformGroups()) return
  botSaving.value = true
  try {
    applyBotSettings(await saveBotSettings({ ...botForm }))
    ElMessage.success('机器人设置已保存，立即生效')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    botSaving.value = false
  }
}

async function loadForbiddenWords() {
  forbiddenLoading.value = true
  try {
    forbiddenWords.value = await listBotForbiddenWords()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载违禁词失败')
  } finally {
    forbiddenLoading.value = false
  }
}

async function addForbiddenWord() {
  const word = newForbiddenWord.value.trim()
  if (!word) {
    ElMessage.warning('请填写违禁词')
    return
  }
  forbiddenAdding.value = true
  try {
    await addBotForbiddenWord(word)
    newForbiddenWord.value = ''
    await loadForbiddenWords()
    ElMessage.success(`已添加违禁词：${word}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '添加失败')
  } finally {
    forbiddenAdding.value = false
  }
}

async function removeForbiddenWord(row: BotForbiddenWord) {
  try {
    await ElMessageBox.confirm(`确定删除违禁词「${row.word}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteBotForbiddenWord(row.id)
    await loadForbiddenWords()
    ElMessage.success('已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

const lotteryAverageMultiplier = ref(1)
const lotteryEffectiveRatio = ref(3)
const lotteryPreviewRows = ref<Array<{
  label: string
  minReward: number
  maxReward: number
  capped: boolean
}>>([])

function recalculateLotteryPreview() {
  const ratio = Number(lotteryForm.payoutRatioPercent || 0)
  const multiplierMin = Number(lotteryForm.multiplierMin || 0)
  const multiplierMax = Number(lotteryForm.multiplierMax || 0)
  const maxReward = Number(lotteryForm.maxReward || 0)
  const averageMultiplier = (multiplierMin + multiplierMax) / 2
  lotteryAverageMultiplier.value = averageMultiplier
  lotteryEffectiveRatio.value = ratio * averageMultiplier
  lotteryPreviewRows.value = Array.from({ length: 91 }, (_, index) => index + 10).map((usage) => {
    const base = usage * ratio / 100
    const rawMin = floorMoney(base * multiplierMin)
    const rawMax = floorMoney(base * multiplierMax)
    const finalMin = floorMoney(Math.min(rawMin, maxReward))
    const finalMax = floorMoney(Math.min(rawMax, maxReward))
    return {
      label: `$${usage.toFixed(2)}`,
      minReward: finalMin,
      maxReward: finalMax,
      capped: rawMax > maxReward,
    }
  })
}

function recalculateLotteryPreviewSoon() {
  void nextTick(recalculateLotteryPreview)
}

watch(lotteryForm, () => {
  recalculateLotteryPreview()
}, { deep: true, immediate: true })

function floorMoney(value: number): number {
  return Math.floor((value + 1e-9) * 100) / 100
}

function applyLotterySettings(settings: BotLotterySettings) {
  Object.assign(lotteryForm, {
    enabled: settings.enabled === true,
    minUsageDaily: Number(settings.minUsageDaily ?? 10),
    payoutRatioPercent: Number(settings.payoutRatioPercent ?? 3),
    multiplierMin: Number(settings.multiplierMin ?? 0.8),
    multiplierMax: Number(settings.multiplierMax ?? 1.2),
    maxReward: Number(settings.maxReward ?? 5),
    dailyBudget: Number(settings.dailyBudget ?? 50),
  })
  lotteryUpdatedAt.value = settings.updatedAt ? new Date(settings.updatedAt).toLocaleString('zh-CN') : ''
}

async function loadLottery() {
  lotteryLoading.value = true
  try {
    const [settings, draws] = await Promise.all([
      getBotLotterySettings(),
      listBotLotteryDraws(30),
    ])
    applyLotterySettings(settings)
    lotteryDraws.value = draws
    await nextTick()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载抽奖设置失败')
  } finally {
    lotteryLoading.value = false
  }
}

async function saveLotterySettingsForm() {
  if (lotteryForm.multiplierMax < lotteryForm.multiplierMin) {
    ElMessage.error('随机系数上限不能小于下限')
    return
  }
  lotterySaving.value = true
  try {
    const settings = await saveBotLotterySettings({ ...lotteryForm })
    lotteryUpdatedAt.value = settings.updatedAt ? new Date(settings.updatedAt).toLocaleString('zh-CN') : ''
    lotteryDraws.value = await listBotLotteryDraws(30)
    ElMessage.success('抽奖设置已保存，立即生效')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    lotterySaving.value = false
  }
}

function lotteryStatusText(status: BotLotteryDraw['status']): string {
  return {
    PENDING: '处理中',
    CREDITED: '已发放',
    FAILED: '发放失败',
    NO_PRIZE: '未中奖',
  }[status] || status
}

function lotteryStatusType(status: BotLotteryDraw['status']): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'PENDING':
      return 'warning'
    case 'CREDITED':
      return 'success'
    case 'FAILED':
      return 'danger'
    default:
      return 'info'
  }
}

const identityLoading = ref(false)
const identitySyncing = ref(false)
const adminAdding = ref(false)
const newAdminEmail = ref('')
const newAdminRemark = ref('')
const identity = reactive<BotIdentityOverview>({
  enabled: true,
  qqLocalPartMatch: true,
  sub2Api: { available: false, userCount: 0, adminCount: 0, syncedAt: null },
  customAdmins: [],
})

const identitySyncTime = computed(() =>
  identity.sub2Api.syncedAt ? new Date(identity.sub2Api.syncedAt).toLocaleString('zh-CN') : '从未同步',
)

function formatIdentityTime(value: string | null): string {
  return value ? new Date(value).toLocaleString('zh-CN') : '-'
}

function applyIdentity(next: BotIdentityOverview) {
  Object.assign(identity, {
    enabled: next.enabled,
    qqLocalPartMatch: next.qqLocalPartMatch,
    sub2Api: { ...next.sub2Api },
    customAdmins: [...(next.customAdmins ?? [])],
  })
}

async function loadIdentity() {
  identityLoading.value = true
  try {
    applyIdentity(await getBotIdentity())
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载身份识别设置失败')
  } finally {
    identityLoading.value = false
  }
}

async function syncIdentity() {
  identitySyncing.value = true
  try {
    applyIdentity(await syncBotIdentity())
    ElMessage.success(`同步完成，当前缓存 ${identity.sub2Api.userCount} 个平台用户`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '同步失败')
  } finally {
    identitySyncing.value = false
  }
}

async function addAdmin() {
  const email = newAdminEmail.value.trim()
  if (!email) {
    ElMessage.warning('请填写管理员邮箱')
    return
  }
  adminAdding.value = true
  try {
    await addBotAdmin(email, newAdminRemark.value.trim())
    newAdminEmail.value = ''
    newAdminRemark.value = ''
    await loadIdentity()
    ElMessage.success('已添加自定义管理员')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '添加失败')
  } finally {
    adminAdding.value = false
  }
}

async function removeAdmin(row: BotAdmin) {
  try {
    await ElMessageBox.confirm(`确定删除自定义管理员 ${row.email}？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteBotAdmin(row.id)
    await loadIdentity()
    ElMessage.success('已删除')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

onMounted(() => {
  load()
  loadNotification()
  loadBot()
  loadForbiddenWords()
  loadLottery()
  loadIdentity()
})

</script>

<style scoped>
.settings-tabs {
  margin-top: 4px;
}

.settings-tabs :deep(.el-tabs__header) {
  margin-bottom: 18px;
  padding: 0 4px;
}

.settings-tabs :deep(.el-tabs__item) {
  font-size: 14px;
  font-weight: 600;
}

.settings-tabs :deep(.el-tabs__content) {
  overflow: visible;
}

.settings-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.settings-toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.settings-toggle-title {
  margin-bottom: 4px;
  color: #303133;
  font-size: 14px;
}

.identity-summary {
  margin-bottom: 14px;
}

.identity-alert {
  margin-bottom: 14px;
}

.identity-section-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin: 6px 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.identity-section-title small {
  font-weight: 400;
  color: #909399;
}

.identity-add-row {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}

.identity-table {
  width: 100%;
}

.forbidden-alert {
  margin-bottom: 14px;
}

.forbidden-add-row {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}

.forbidden-table {
  width: 100%;
}

.lottery-alert {
  margin-bottom: 16px;
}

.lottery-settings-layout {
  display: grid;
  grid-template-columns: minmax(420px, .95fr) minmax(480px, 1.05fr);
  gap: 24px;
  align-items: start;
}

.lottery-settings-form {
  min-width: 0;
}

.lottery-settings-preview {
  position: sticky;
  top: 92px;
  min-width: 0;
  padding: 16px;
  border: 1px solid #e3ebe8;
  border-radius: 12px;
  background: #fbfdfc;
}

.lottery-preview-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.lottery-preview-heading strong {
  color: #303133;
  font-size: 15px;
}

.lottery-preview-heading span {
  color: #7b8b87;
  font-size: 11px;
}

.lottery-preview {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.lottery-preview-item {
  display: grid;
  gap: 4px;
  padding: 14px;
  border: 1px solid #e3ebe8;
  border-radius: 10px;
  background: #f8fbfa;
}

.lottery-preview-item span,
.lottery-preview-item small {
  color: #7b8b87;
  font-size: 11px;
}

.lottery-preview-item strong {
  color: #1f7a69;
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}

.lottery-preview-table,
.lottery-draw-table {
  width: 100%;
  margin-bottom: 12px;
}

.lottery-ladder-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.lottery-ladder-title strong {
  color: #303133;
  font-size: 14px;
}

.lottery-ladder-title span {
  color: #7b8b87;
  font-size: 11px;
}

@media (max-width: 1100px) {
  .lottery-settings-layout {
    grid-template-columns: 1fr;
  }

  .lottery-settings-preview {
    position: static;
  }
}

@media (max-width: 640px) {
  .lottery-preview {
    grid-template-columns: 1fr;
  }
}

.settings-test-email {
  display: flex;
  gap: 12px;
  align-items: center;
}
</style>
