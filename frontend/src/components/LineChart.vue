<template>
  <div class="monitor-chart">
    <div class="monitor-chart-legend">
      <span><i class="legend-dot balance"></i>余额</span>
      <span><i class="legend-dot usage"></i>已用额度</span>
    </div>
    <EChart :option="option" height="300px" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsCoreOption } from 'echarts/core'
import type { MetricPoint } from '../types'
import EChart from './EChart.vue'

const props = defineProps<{ points: MetricPoint[] }>()

const option = computed<EChartsCoreOption>(() => ({
  animationDuration: 600,
  color: ['#1f7a69', '#5574a8'],
  grid: { left: 48, right: 22, top: 34, bottom: 34 },
  tooltip: {
    trigger: 'axis',
    backgroundColor: 'rgba(24, 51, 47, .94)',
    borderWidth: 0,
    textStyle: { color: '#fff', fontSize: 11 },
  },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: props.points.map(point => point.time),
    axisLine: { lineStyle: { color: '#dfe9e5' } },
    axisTick: { show: false },
    axisLabel: { color: '#8d9a97', fontSize: 10 },
  },
  yAxis: {
    type: 'value',
    axisLabel: { color: '#8d9a97', fontSize: 10 },
    splitLine: { lineStyle: { color: '#edf1ef' } },
  },
  series: [
    {
      name: '余额',
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 7,
      data: props.points.map(point => point.balance),
      lineStyle: { width: 3 },
      areaStyle: { color: 'rgba(31, 122, 105, .10)' },
    },
    {
      name: '已用额度',
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      data: props.points.map(point => point.usedQuota),
      lineStyle: { width: 2.5 },
      areaStyle: { color: 'rgba(85, 116, 168, .08)' },
    },
  ],
}))
</script>