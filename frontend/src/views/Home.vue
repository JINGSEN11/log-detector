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
const maxSeverity = computed(() => Math.max(1, ...(data.value?.severityDistribution.map(item => item.count) ?? [])))
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
    <header class="heading">
      <div>
        <div class="eyebrow">SECURITY OVERVIEW</div>
        <h1>安全概览</h1>
      </div>
    </header>

    <el-alert v-if="loadFailed" type="error" :closable="false" title="统计数据加载失败，请稍后重试。" />

    <template v-if="data">
      <section class="metrics" aria-label="安全统计">
        <article class="metric metric--primary">
          <span class="metric__label">攻击事件</span>
          <strong class="metric__value">{{ data.eventCount }}</strong>
          <span class="metric__sub">已聚合的攻击事件总数</span>
        </article>
        <article class="metric">
          <span class="metric__label">严重 / 高危</span>
          <strong class="metric__value">{{ data.highSeverityEventCount }}</strong>
        </article>
        <article class="metric">
          <span class="metric__label">已解析日志</span>
          <strong class="metric__value">{{ data.logCount }}</strong>
        </article>
        <article class="metric">
          <span class="metric__label">AI 研判</span>
          <strong class="metric__value">{{ data.aiAnalysisCount }}</strong>
        </article>
      </section>

      <section class="panel">
        <div class="panel__head"><h2>24 小时事件趋势</h2></div>
        <div class="trend-scroll">
          <div class="trend" role="img" aria-label="24 小时事件数柱状图">
            <div v-for="bucket in data.trend24h" :key="bucket.hour" class="bar-slot">
              <div class="bar" :style="{ height: `${bucket.eventCount / maxTrend * 100}%` }" :title="`${bucket.hour} · ${bucket.eventCount}`"></div>
            </div>
          </div>
          <div class="axis"><span>{{ data.trend24h[0]?.hour }}</span><span>{{ data.trend24h[data.trend24h.length - 1]?.hour }}</span></div>
        </div>
      </section>

      <div class="grid">
        <section class="panel">
          <div class="panel__head"><h2>严重度构成</h2></div>
          <div v-if="data.severityDistribution.length" class="severity">
            <div v-for="item in data.severityDistribution" :key="item.severity" class="severity__row">
              <span class="severity__dot" :class="`sev-${(item.severity || 'LOW').toLowerCase()}`"></span>
              <span class="severity__name">{{ item.severity }}</span>
              <div class="severity__track">
                <div class="severity__fill" :class="`sev-${(item.severity || 'LOW').toLowerCase()}`" :style="{ width: `${item.count / maxSeverity * 100}%` }"></div>
              </div>
              <span class="severity__count">{{ item.count }}</span>
            </div>
          </div>
          <p v-else class="empty">暂无事件</p>
        </section>

        <section class="panel">
          <div class="panel__head"><h2>高频来源</h2></div>
          <ol v-if="data.topSources.length" class="sources">
            <li v-for="(item, index) in data.topSources" :key="index" class="sources__row">
              <span class="sources__rank">{{ String(index + 1).padStart(2, '0') }}</span>
              <code class="sources__ip">{{ item.clientIpMask }}</code>
              <span class="sources__count">{{ item.eventCount }}</span>
            </li>
          </ol>
          <p v-else class="empty">暂无事件</p>
        </section>
      </div>

      <section class="panel">
        <div class="panel__head">
          <h2>最新事件</h2>
          <router-link class="panel__more" to="/events">查看全部 →</router-link>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>ID</th><th>来源 IP</th><th>开始时间</th><th>严重度</th><th>风险</th><th>状态</th><th></th></tr></thead>
            <tbody>
              <tr v-for="event in data.latestEvents" :key="event.id">
                <td class="num">{{ event.id }}</td>
                <td class="num">{{ event.clientIpMask }}</td>
                <td class="num">{{ event.windowStart }}</td>
                <td><span class="badge" :class="`sev-${(event.severity || 'LOW').toLowerCase()}`">{{ event.severity }}</span></td>
                <td class="num">{{ event.riskScore }}</td>
                <td>{{ event.status }}</td>
                <td><router-link :to="{ path: '/events', query: { eventId: event.id } }">查看</router-link></td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="!data.latestEvents.length" class="empty">暂无事件，请先导入并检测日志。</p>
      </section>
    </template>

    <section v-if="auth.role === 'ADMIN'" class="panel">
      <div class="panel__head"><h2>事件研判调用与缓存</h2><el-button size="small" @click="loadCost">刷新</el-button></div>
      <el-alert v-if="costFailed" type="error" :closable="false" title="研判统计加载失败" />
      <template v-if="cost">
        <div class="table-wrap"><table><tbody>
          <tr><th>研判请求</th><td>{{ cost.requestCount }}</td><th>缓存命中</th><td>{{ cost.cacheHits }}（{{ cost.cacheHitRate == null ? '—' : `${(cost.cacheHitRate * 100).toFixed(1)}%` }}）</td><th>模型尝试次数</th><td>{{ cost.callCount }}</td></tr>
          <tr><th>输入 tokens</th><td>{{ cost.promptTokens }}</td><th>输出 tokens</th><td>{{ cost.completionTokens }}</td><th>平均尝试耗时</th><td>{{ cost.averageLatencyMs == null ? '—' : `${Number(cost.averageLatencyMs).toFixed(0)} ms` }}</td></tr>
          <tr><th>估计节省 tokens</th><td>{{ cost.savedTokensEstimate }}</td><th>token 未知尝试</th><td>{{ cost.unknownTokenCalls }}</td></tr>
        </tbody></table></div>
        <h3>最近 100 次模型尝试</h3><div class="table-wrap"><table><thead><tr><th>ID</th><th>时间</th><th>事件</th><th>模型</th><th>结果</th><th>耗时 ms</th><th>输入 tokens</th><th>输出 tokens</th></tr></thead><tbody><tr v-for="item in cost.recentCalls" :key="item.id"><td>{{ item.id }}</td><td>{{ item.createdAt }}</td><td>{{ item.eventId }}</td><td>{{ item.model }}</td><td>{{ item.outcome }}</td><td>{{ item.elapsedMs }}</td><td>{{ item.promptTokens ?? '未知' }}</td><td>{{ item.completionTokens ?? '未知' }}</td></tr></tbody></table></div>
        <p v-if="!cost.recentCalls.length" class="empty">暂无模型尝试记录。</p>
      </template>
    </section>
  </div>
</template>

<style scoped>
.dashboard { max-width: 1500px; margin: auto; color: var(--csys-text); }
.heading { margin-bottom: 24px; }
.eyebrow { font-size: 11px; font-weight: 600; letter-spacing: 0.12em; color: var(--csys-faint); text-transform: uppercase; margin-bottom: 6px; }
.heading h1 { font-size: 26px; letter-spacing: -0.02em; margin: 0; }
.metrics { display: grid; grid-template-columns: 2fr 1fr 1fr 1fr; gap: 12px; }
.metric { background: var(--csys-surface); border: 1px solid var(--csys-border-subtle); border-radius: 6px; padding: 16px 18px; }
.metric--primary { border-left: 2px solid var(--csys-accent); }
.metric__label { display: block; font-size: 12px; color: var(--csys-muted); }
.metric__value { display: block; font-size: 24px; font-weight: 600; letter-spacing: -0.02em; margin-top: 10px; font-variant-numeric: tabular-nums; }
.metric--primary .metric__value { font-size: 40px; letter-spacing: -0.03em; }
.metric__sub { display: block; font-size: 12px; color: var(--csys-faint); margin-top: 6px; }
.panel { background: var(--csys-surface); border: 1px solid var(--csys-border-subtle); border-radius: 8px; padding: 18px 20px; margin-top: 16px; }
.panel__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.panel__head h2 { font-size: 14px; font-weight: 600; margin: 0; color: var(--csys-text); }
.panel__more { font-size: 13px; color: var(--csys-muted); }
.panel__more:hover { color: var(--csys-accent); }
.empty { color: var(--csys-faint); font-size: 13px; margin: 12px 0 0; }
.trend-scroll { overflow-x: auto; }
.trend { height: 140px; display: flex; align-items: flex-end; gap: 3px; border-bottom: 1px solid var(--csys-border-subtle); padding-top: 12px; }
.bar-slot { height: 100%; flex: 1; display: flex; align-items: flex-end; justify-content: center; min-width: 0; }
.bar { width: 100%; max-width: 14px; border-radius: 2px 2px 0 0; background: var(--csys-accent); opacity: 0.75; }
.bar:hover { opacity: 1; }
.axis { display: flex; justify-content: space-between; color: var(--csys-faint); font-size: 11px; margin-top: 8px; font-variant-numeric: tabular-nums; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.severity { display: flex; flex-direction: column; gap: 12px; }
.severity__row { display: flex; align-items: center; gap: 10px; }
.severity__dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.severity__name { width: 64px; flex: none; font-size: 12px; color: var(--csys-muted); }
.severity__track { flex: 1; height: 4px; background: var(--csys-surface-2); border-radius: 2px; overflow: hidden; }
.severity__fill { height: 100%; border-radius: 2px; transition: width 0.3s; }
.severity__count { width: 40px; text-align: right; font-size: 12px; color: var(--csys-text); font-variant-numeric: tabular-nums; }
.sev-critical { --sev: var(--csys-critical); }
.sev-high { --sev: var(--csys-high); }
.sev-medium { --sev: var(--csys-medium); }
.sev-low { --sev: var(--csys-low); }
.severity__dot.sev-critical, .severity__dot.sev-high, .severity__dot.sev-medium, .severity__dot.sev-low { background: var(--sev); }
.severity__fill.sev-critical, .severity__fill.sev-high, .severity__fill.sev-medium, .severity__fill.sev-low { background: var(--sev); }
.badge.sev-critical, .badge.sev-high, .badge.sev-medium, .badge.sev-low { color: var(--sev); background: color-mix(in srgb, var(--sev) 14%, transparent); }
.sources { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
.sources__row { display: flex; align-items: center; gap: 12px; padding: 8px 0; border-bottom: 1px solid var(--csys-border-subtle); }
.sources__row:last-child { border-bottom: none; }
.sources__rank { width: 22px; flex: none; font-size: 11px; color: var(--csys-faint); font-variant-numeric: tabular-nums; }
.sources__ip { flex: 1; font-size: 13px; color: var(--csys-text); font-variant-numeric: tabular-nums; }
.sources__count { flex: none; font-size: 13px; color: var(--csys-muted); font-variant-numeric: tabular-nums; }
.table-wrap { overflow: auto; }
table { width: 100%; border-collapse: collapse; font-size: 13px; text-align: left; }
th, td { padding: 10px 8px; border-bottom: 1px solid var(--csys-border-subtle); }
th { color: var(--csys-faint); font-weight: 500; font-size: 12px; }
td { color: var(--csys-text); }
.num { font-variant-numeric: tabular-nums; }
.badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
a { color: var(--csys-accent); }
h3 { font-size: 13px; margin: 16px 0 8px; color: var(--csys-muted); }
@media (max-width: 850px) {
  .metrics { grid-template-columns: 1fr 1fr; }
  .grid { grid-template-columns: 1fr; gap: 0; }
}
@media (max-width: 550px) {
  .heading { align-items: flex-start; flex-direction: column; }
  .metrics { gap: 10px; }
  .metric, .panel { padding: 14px; }
}
</style>
