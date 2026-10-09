<template>
  <div ref="chartEl" class="echart-host" :style="{ height }"></div>
</template>

<script setup lang="ts">
import * as echarts from 'echarts/core'
import type { ECharts, EChartsCoreOption } from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, CanvasRenderer])

const props = withDefaults(defineProps<{ option: EChartsCoreOption; height?: string }>(), {
  height: '300px',
})

const chartEl = ref<HTMLDivElement | null>(null)
let chart: ECharts | null = null
let observer: ResizeObserver | null = null
let resizeFrame = 0

/**
 * 容器尺寸变化后同步 ECharts 画布尺寸。
 *
 * 抽屉、对话框打开时宽度是 0 → 目标宽度的过渡动画，如果只在挂载时 init 一次，
 * ECharts 会把内部尺寸记成动画中途的窄宽度，表现为「数据挤在最左边、坐标轴标签消失」。
 */
function scheduleResize() {
  if (resizeFrame) cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => {
    resizeFrame = 0
    chart?.resize()
  })
}

function render() {
  if (!chartEl.value) return
  if (!chart) {
    chart = echarts.init(chartEl.value)
  }
  chart.setOption(props.option, true)
  // setOption 之后容器可能才拿到最终宽度，补一次尺寸同步
  chart.resize()
}

function resize() {
  chart?.resize()
}

onMounted(async () => {
  await nextTick()
  render()
  window.addEventListener('resize', scheduleResize)
  if (typeof ResizeObserver !== 'undefined' && chartEl.value) {
    observer = new ResizeObserver(scheduleResize)
    observer.observe(chartEl.value)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', scheduleResize)
  if (resizeFrame) {
    cancelAnimationFrame(resizeFrame)
    resizeFrame = 0
  }
  observer?.disconnect()
  observer = null
  chart?.dispose()
  chart = null
})

watch(() => props.option, render, { deep: true })

defineExpose({ resize: scheduleResize })
</script>