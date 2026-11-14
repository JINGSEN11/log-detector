<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { dashboardApi, type AnalysisCost, type DashboardData } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const cost = ref<AnalysisCost | null>(null)
const costFailed = ref(false)
async function loadCost(): Promise<void> {
  costFailed.value = false
  try { cost.value = await dashboardApi.analysisCost() } catch { costFailed.value = true }
}

const data = ref<DashboardData | null>(null)
const loadFailed = ref(false)
const maxTrend = computed(() => Math.max(1, ...(data.value?.trend24h.map(item => item.eventCount) ?? [])))
async function load(): Promise<void> {
  loadFailed.value = false
  try { data.value = await dashboardApi.overview() } catch { data.value = null; loadFailed.value = true }
}

onMounted(() => {
  void load()
  if (auth.role === 'ADMIN') void loadCost()
})
</script>

<template>
  <div class="dashboard">
    <header class="heading"><div><h1>安全概览</h1><p>基于已入库日志和已生成事件的统计</p></div></header>
    <el-alert v-if="loadFailed" type="error" :closable="false" title="统计数据加载失败，请稍后重试。" />
    <template v-if="data">
      <section class="metrics" aria-label="安全统计">
        <article><span>攻击事件</span><strong>{{ data.eventCount }}</strong></article>
        <article><span>严重 / 高危事件</span><strong>{{ data.highSeverityEventCount }}</strong></article>
        <article><span>已解析日志</span><strong>{{ data.logCount }}</strong></article>
        <article><span>AI 研判量</span><strong>{{ data.aiAnalysisCount }}</strong></article>
      </section>
      <section class="panel"><h2>24 小时事件趋势</h2>
        <div class="trend-scroll"><div class="trend-chart">
          <div class="trend" role="group" aria-label="24 小时事件数柱状图，详细数值见下方表格">
            <div v-for="bucket in data.trend24h" :key="bucket.hour" class="bar-slot"><div class="bar" :style="{ height: `${bucket.eventCount / maxTrend * 100}%` }"><span v-if="bucket.eventCount > 0" class="bar-value" aria-hidden="true">{{ bucket.eventCount }}</span></div></div>
          </div>
          <div class="axis"><span>{{ data.trend24h[0]?.hour }}</span><span>{{ data.trend24h[data.trend24h.length - 1]?.hour }}</span></div>
        </div></div>
        <details><summary>查看趋势数据表</summary><div class="table-wrap"><table><thead><tr><th>小时</th><th>事件数</th></tr></thead><tbody><tr v-for="bucket in data.trend24h" :key="bucket.hour"><td>{{ bucket.hour }}</td><td>{{ bucket.eventCount }}</td></tr></tbody></table></div></details>
      </section>
      <div class="grid"><section class="panel"><h2>严重度构成</h2><p>按事件最高严重度统计</p><table><thead><tr><th>严重度</th><th>事件数</th></tr></thead><tbody><tr v-for="item in data.severityDistribution" :key="item.severity"><td>{{ item.severity }}</td><td>{{ item.count }}</td></tr></tbody></table><p v-if="!data.severityDistribution.length">暂无事件</p></section>
        <section class="panel"><h2>高频来源</h2><p>按来源伪名聚合；掩码相同的来源可能是不同 IP。</p><table><thead><tr><th>来源 IP（掩码）</th><th>事件数</th></tr></thead><tbody><tr v-for="(item, index) in data.topSources" :key="index"><td>{{ item.clientIpMask }}</td><td>{{ item.eventCount }}</td></tr></tbody></table><p v-if="!data.topSources.length">暂无事件</p></section></div>
      <section class="panel"><div class="section-heading"><div><h2>最新事件</h2><p>按事件开始时间倒序，最多显示 10 条</p></div><router-link to="/events">查看全部事件 →</router-link></div><div class="table-wrap"><table><thead><tr><th>事件 ID</th><th>来源 IP（掩码）</th><th>开始时间</th><th>严重度</th><th>风险分</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="event in data.latestEvents" :key="event.id"><td>{{ event.id }}</td><td>{{ event.clientIpMask }}</td><td>{{ event.windowStart }}</td><td>{{ event.severity }}</td><td>{{ event.riskScore }}</td><td>{{ event.status }}</td><td><router-link :to="{ path: '/events', query: { eventId: event.id } }">查看证据</router-link></td></tr></tbody></table></div><p v-if="!data.latestEvents.length">暂无事件，请先导入并检测日志。</p></section>
    </template>
    <section v-if="auth.role === 'ADMIN'" class="panel">
      <div class="section-heading"><h2>事件研判调用与缓存</h2><el-button @click="loadCost">刷新统计</el-button></div>
      <el-alert v-if="costFailed" type="error" :closable="false" title="研判统计加载失败" />
      <template v-if="cost">
        <div class="table-wrap"><table><tbody>
          <tr><th>研判请求</th><td>{{ cost.requestCount }}</td><th>缓存命中</th><td>{{ cost.cacheHits }}（{{ cost.cacheHitRate == null ? '—' : `${(cost.cacheHitRate * 100).toFixed(1)}%` }}）</td><th>模型尝试次数</th><td>{{ cost.callCount }}</td></tr>
          <tr><th>输入 tokens</th><td>{{ cost.promptTokens }}</td><th>输出 tokens</th><td>{{ cost.completionTokens }}</td><th>平均尝试耗时</th><td>{{ cost.averageLatencyMs == null ? '—' : `${Number(cost.averageLatencyMs).toFixed(0)} ms` }}</td></tr>
          <tr><th>估计节省 tokens</th><td>{{ cost.savedTokensEstimate }}</td><th>token 未知尝试</th><td>{{ cost.unknownTokenCalls }}</td></tr>
        </tbody></table></div>
        <h3>最近 100 次模型尝试</h3><div class="table-wrap"><table><thead><tr><th>ID</th><th>时间</th><th>事件</th><th>模型</th><th>结果</th><th>耗时 ms</th><th>输入 tokens</th><th>输出 tokens</th></tr></thead><tbody><tr v-for="item in cost.recentCalls" :key="item.id"><td>{{ item.id }}</td><td>{{ item.createdAt }}</td><td>{{ item.eventId }}</td><td>{{ item.model }}</td><td>{{ item.outcome }}</td><td>{{ item.elapsedMs }}</td><td>{{ item.promptTokens ?? '未知' }}</td><td>{{ item.completionTokens ?? '未知' }}</td></tr></tbody></table></div>
        <p v-if="!cost.recentCalls.length">暂无模型尝试记录。</p>
      </template>
    </section>
  </div>
</template>

<style scoped>
.trend-scroll{overflow-x:auto}.trend-chart{min-width:720px}.trend{padding-top:20px}.bar{position:relative}.bar-value{position:absolute;bottom:100%;left:50%;transform:translateX(-50%);padding-bottom:3px;color:#34445b;font-size:11px;line-height:14px;white-space:nowrap}
.dashboard{max-width:1500px;margin:auto;color:#202c42}.heading,.section-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.heading{margin-bottom:24px}.heading h1{font-size:26px;margin:0}.heading p,.panel p{color:#78869a;font-size:13px}.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px}.metrics article,.panel{background:#fff;border:1px solid #e8edf4;border-radius:12px;padding:20px}.metrics span,.metrics small{display:block;color:#78869a;font-size:12px}.metrics strong{display:block;font-size:28px;margin:14px 0 8px}.panel{margin-top:20px}.panel h2{font-size:16px;margin:0}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;font-size:13px;text-align:left}th,td{padding:11px 8px;border-bottom:1px solid #edf1f6}th{color:#78869a;font-weight:500}td{color:#34445b}a{color:#345de8}.trend{height:150px;display:flex;align-items:flex-end;gap:4px;border-bottom:1px solid #dce3ed;margin-top:20px}.bar-slot{height:100%;flex:1;display:flex;align-items:flex-end;justify-content:center;min-width:0}.bar{width:100%;max-width:24px;border-radius:4px 4px 0 0;background:#345de8}.axis{display:flex;justify-content:space-between;gap:10px;color:#78869a;font-size:11px;margin-top:8px}details{margin-top:12px;font-size:12px}summary{cursor:pointer;color:#526078}.model-actions{text-align:right;margin-top:12px}.answer{white-space:pre-wrap;overflow-wrap:anywhere;background:#f7f9fc;padding:15px;border-radius:8px;font-size:13px}@media(max-width:850px){.metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.grid{grid-template-columns:1fr;gap:0}}@media(max-width:550px){.heading{align-items:flex-start;flex-direction:column}.metrics{gap:10px}.metrics article,.panel{padding:14px}.axis{font-size:10px}}
</style>
