<template>
  <EChart :option="option" height="330px" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import type { Channel } from '../types'
import EChart from './EChart.vue'

const props = defineProps<{ channels: Channel[] }>()

const option = computed<EChartsCoreOption>(() => {
  const channels = [...props.channels].reverse()
  return {
    animationDuration: 600,
    grid: { left: 112, right: 28, top: 22, bottom: 24 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      backgroundColor: 'rgba(24, 51, 47, .94)',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 11 },
    },
    xAxis: {
      type: 'value',
      axisLabel: { color: '#8d9a97', fontSize: 10 },
      splitLine: { lineStyle: { color: '#edf1ef' } },
    },
    yAxis: {
      type: 'category',
      data: channels.map(channel => channel.name),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#53645f', fontSize: 10, width: 104, overflow: 'truncate' },
    },
    series: [
      {
        name: '倍率',
        type: 'bar',
        data: channels.map(channel => channel.ratio),
        barWidth: 12,
        itemStyle: {
          borderRadius: [0, 6, 6, 0],
          color: '#2b7b68',
        },
      },
    ],
  }
})
</script>