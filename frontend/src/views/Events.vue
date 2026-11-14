<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { formatRule, formatRules } from '../utils/rules'
import { dashboardApi, eventApi, type DetectionEvent, type EventEvidence, type EventAnalysis, type LogContext, type EventStatus, type EventTimelineItem } from '../api'

const route = useRoute()
const batchId = ref<number>()
const activeBatchId = ref<number>()
const loading = ref(false)
const eventsError = ref(false)
let eventsVersion = 0
const detecting = ref(false)
const events = ref<DetectionEvent[]>([])
const detail = ref<(DetectionEvent & { evidence: EventEvidence[] }) | null>(null)
const detailLoading = ref(false)
const detailError = ref(false)
const detailVisible = ref(false)
const detailId = ref<number | null>(null)
const detailTab = ref('evidence')
const analysis = ref<EventAnalysis | null>(null)
const analyzing = ref(false)
const latestLoading = ref(false)
const latestError = ref(false)
const history = ref<EventAnalysis[]>([])
const historyLoading = ref(false)
const historyError = ref(false)
const historyPage = ref(1)
const historyTotal = ref(0)
const historySize = 10
let latestVersion = 0
let historyVersion = 0
const statuses: Array<{ value: EventStatus; label: string }> = [
  { value: 'NEW', label: '新建' }, { value: 'INVESTIGATING', label: '调查中' },
  { value: 'CONFIRMED', label: '已确认' }, { value: 'FALSE_POSITIVE', label: '误报' }, { value: 'CLOSED', label: '已关闭' }
]
const selectedStatus = ref<EventStatus>('NEW')
const note = ref('')
const timeline = ref<EventTimelineItem[]>([])
const timelineLoading = ref(false)
const saving = ref(false)
const exporting = ref(false)
let selectionVersion = 0
let contextVersion = 0
const context = ref<LogContext | null>(null)
const contextLoading = ref(false)
const contextVisible = ref(false)

// 事件筛选与排序（纯前端）
const severityFilter = ref('ALL')
const statusFilter = ref('ALL')
const sortBy = ref<'risk' | 'time'>('risk')
const statusMeta: Record<string, { label: string; cls: string }> = {
  NEW: { label: '新建', cls: 'st-new' },
  INVESTIGATING: { label: '调查中', cls: 'st-investigating' },
  CONFIRMED: { label: '已确认', cls: 'st-confirmed' },
  FALSE_POSITIVE: { label: '误报', cls: 'st-false' },
  CLOSED: { label: '已关闭', cls: 'st-closed' }
}
const statusLabel = (s: string): string => statusMeta[s]?.label || s
const statusClass = (s: string): string => statusMeta[s]?.cls || 'st-closed'
const riskLevel = (score: number): string => score >= 80 ? 'risk-high' : score >= 60 ? 'risk-mid' : score >= 40 ? 'risk-low' : 'risk-min'
const filteredEvents = computed(() => {
  let list = events.value
  if (severityFilter.value !== 'ALL') list = list.filter(e => e.severity === severityFilter.value)
  if (statusFilter.value !== 'ALL') list = list.filter(e => e.status === statusFilter.value)
  return [...list].sort((a, b) => sortBy.value === 'risk' ? b.riskScore - a.riskScore : b.windowStart.localeCompare(a.windowStart))
})

async function showContext(logId: number): Promise<void> {
  const version = ++contextVersion
  context.value = null
  contextLoading.value = true
  contextVisible.value = true
  try {
    const response = await dashboardApi.context(logId)
    if (version === contextVersion) context.value = response
  } finally { if (version === contextVersion) contextLoading.value = false }
}

async function loadEvents(): Promise<void> {
  const version = ++eventsVersion
  const filter = activeBatchId.value
  loading.value = true
  eventsError.value = false
  try {
    const result = await eventApi.list(filter)
    if (version === eventsVersion) events.value = result
  } catch {
    if (version === eventsVersion) {
      events.value = []
      eventsError.value = true
    }
  } finally { if (version === eventsVersion) loading.value = false }
}

function showAllEvents(): void {
  activeBatchId.value = undefined
  events.value = []
  void loadEvents()
}

function showBatchEvents(): void {
  if (!batchId.value || !Number.isSafeInteger(batchId.value) || batchId.value < 1) {
    ElMessage.warning('请输入有效的日志批次 ID')
    return
  }
  activeBatchId.value = batchId.value
  events.value = []
  void loadEvents()
}

async function detect(): Promise<void> {
  if (!batchId.value || !Number.isSafeInteger(batchId.value) || batchId.value < 1) {
    ElMessage.warning('请输入有效的日志批次 ID')
    return
  }
  const requestedBatchId = batchId.value
  detecting.value = true
  try {
    const result = await eventApi.detect(requestedBatchId)
    ElMessage.success(`检测完成：${result.hitCount} 条命中，${result.eventCount} 个事件`)
    detailVisible.value = false
    closeDetail()
    activeBatchId.value = requestedBatchId
    events.value = []
    await loadEvents()
  } finally { detecting.value = false }
}

async function loadTimeline(id: number, version: number): Promise<void> {
  timelineLoading.value = true
  try {
    const result = await eventApi.timeline(id)
    if (version === selectionVersion) timeline.value = result
  } finally { if (version === selectionVersion) timelineLoading.value = false }
}

function resetAnalyses(): void {
  ++latestVersion
  ++historyVersion
  analysis.value = null
  analyzing.value = false
  latestLoading.value = false
  latestError.value = false
  history.value = []
  historyLoading.value = false
  historyError.value = false
  historyPage.value = 1
  historyTotal.value = 0
}

async function loadLatest(id: number, selection: number): Promise<void> {
  const version = ++latestVersion
  latestLoading.value = true
  latestError.value = false
  try {
    const result = await eventApi.latestAnalysis(id)
    if (selection === selectionVersion && version === latestVersion) analysis.value = result
  } catch {
    if (selection === selectionVersion && version === latestVersion) latestError.value = true
  } finally {
    if (selection === selectionVersion && version === latestVersion) latestLoading.value = false
  }
}

async function loadHistory(id: number, selection: number, page: number): Promise<void> {
  const version = ++historyVersion
  historyPage.value = page
  historyLoading.value = true
  historyError.value = false
  history.value = []
  try {
    const result = await eventApi.analyses(id, page, historySize)
    if (selection === selectionVersion && version === historyVersion) {
      history.value = result.records
      historyTotal.value = result.total
    }
  } catch {
    if (selection === selectionVersion && version === historyVersion) historyError.value = true
  } finally {
    if (selection === selectionVersion && version === historyVersion) historyLoading.value = false
  }
}

function changeHistoryPage(page: number): void {
  if (detail.value) void loadHistory(detail.value.id, selectionVersion, page)
}

function selectAnalysis(item: EventAnalysis): void {
  ++latestVersion
  latestLoading.value = false
  latestError.value = false
  analysis.value = item
}

async function showDetail(id: number): Promise<void> {
  detailId.value = id
  detailTab.value = 'evidence'
  detailVisible.value = true
  const version = ++selectionVersion
  detail.value = null
  detailError.value = false
  resetAnalyses()
  timeline.value = []
  note.value = ''
  detailLoading.value = true
  try {
    const response = await eventApi.detail(id)
    if (version === selectionVersion) {
      detail.value = response
      selectedStatus.value = response.status as EventStatus
      void loadLatest(id, version)
      void loadHistory(id, version, 1)
      await loadTimeline(id, version)
    }
  } catch {
    if (version === selectionVersion && !detail.value) detailError.value = true
  } finally { if (version === selectionVersion) detailLoading.value = false }
}

function closeDetail(): void {
  ++selectionVersion
  ++contextVersion
  contextVisible.value = false
  contextLoading.value = false
  context.value = null
  detail.value = null
  detailId.value = null
  detailLoading.value = false
  timelineLoading.value = false
  timeline.value = []
  note.value = ''
  resetAnalyses()
}

async function saveStatus(): Promise<void> {
  if (!detail.value || saving.value) return
  if (!note.value.trim()) { ElMessage.warning('请填写处置备注'); return }
  const id = detail.value.id
  const version = selectionVersion
  saving.value = true
  try {
    const updated = await eventApi.updateStatus(id, selectedStatus.value, note.value)
    if (version === selectionVersion) {
      detail.value = updated
      selectedStatus.value = updated.status as EventStatus
      note.value = ''
      await loadTimeline(id, version)
    }
    await loadEvents()
    ElMessage.success('事件状态和备注已保存')
  } finally { saving.value = false }
}

async function download(kind: 'csv' | 'report' | 'pdf'): Promise<void> {
  if (exporting.value || (kind !== 'csv' && !detail.value)) return
  const id = detail.value?.id
  exporting.value = true
  try {
    const blob = kind === 'csv' ? await eventApi.exportCsv(activeBatchId.value) : kind === 'pdf' ? await eventApi.exportPdf(id!) : await eventApi.exportReport(id!)
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = kind === 'csv' ? 'events.csv' : `event-${id}.${kind === 'pdf' ? 'pdf' : 'md'}`
    document.body.appendChild(link)
    link.click()
    link.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } finally { exporting.value = false }
}

async function analyze(): Promise<void> {
  if (!detail.value || analyzing.value) return
  const id = detail.value.id
  const version = selectionVersion
  analyzing.value = true
  try {
    const response = await eventApi.analyze(id)
    if (version === selectionVersion) {
      selectAnalysis(response)
      void loadHistory(id, version, 1)
    }
  } catch {
    // 请求层负责提示错误，保留原有研判结果。
  } finally { if (version === selectionVersion) analyzing.value = false }
}

watch(() => route.query.eventId, value => {
  const id = Number(value)
  if (typeof value === 'string' && Number.isSafeInteger(id) && id > 0) void showDetail(id)
}, { immediate: true })
onMounted(() => { void loadEvents() })
</script>

<template>
  <div class="events">
    <header class="page-header">
      <div>
        <span class="kicker">SECURITY / EVENTS</span>
        <h1>规则检测与事件</h1>
      </div>
    </header>

    <section class="panel detection">
      <div class="panel__head"><h2>运行规则检测</h2></div>
      <div class="detect-row">
        <el-input-number v-model="batchId" :min="1" :precision="0" placeholder="批次 ID" aria-label="批次 ID" controls-position="right" />
        <el-button type="primary" :loading="detecting" @click="detect">开始检测</el-button>
        <el-button :disabled="!batchId" @click="showBatchEvents">查看该批次事件</el-button>
        <el-button :loading="loading" @click="loadEvents">刷新</el-button>
        <el-button :loading="exporting" @click="download('csv')">导出 CSV</el-button>
      </div>
      <p class="hint">批次 ID 在「日志导入」页导入成功后显示；检测完成会自动把列表切到该批次。</p>
    </section>

    <section class="panel event-list">
      <div class="panel__head">
        <h2>攻击事件 <span class="count">{{ filteredEvents.length }}</span></h2>
        <div class="scope">
          <span>{{ activeBatchId ? `批次 #${activeBatchId}` : '全部批次' }}</span>
          <el-button v-if="activeBatchId" link @click="showAllEvents">查看全部</el-button>
        </div>
      </div>
      <div class="filters">
        <el-select v-model="severityFilter" size="small" style="width: 130px">
          <el-option label="全部严重度" value="ALL" />
          <el-option label="CRITICAL" value="CRITICAL" />
          <el-option label="HIGH" value="HIGH" />
          <el-option label="MEDIUM" value="MEDIUM" />
          <el-option label="LOW" value="LOW" />
        </el-select>
        <el-select v-model="statusFilter" size="small" style="width: 130px">
          <el-option label="全部状态" value="ALL" />
          <el-option v-for="s in statuses" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select v-model="sortBy" size="small" style="width: 130px">
          <el-option label="风险分高→低" value="risk" />
          <el-option label="时间新→旧" value="time" />
        </el-select>
      </div>
      <el-alert v-if="eventsError" type="error" :closable="false" title="事件加载失败，请刷新重试" />
      <el-table v-loading="loading" :data="filteredEvents" :empty-text="eventsError ? '事件加载失败' : activeBatchId ? '该批次暂无事件' : '暂无事件，请先检测日志批次'">
        <el-table-column label="ID" width="76"><template #default="s"><code class="mono">{{ s.row.id }}</code></template></el-table-column>
        <el-table-column label="来源 IP" min-width="150"><template #default="s"><code class="mono">{{ s.row.clientIpMask }}</code></template></el-table-column>
        <el-table-column label="开始时间" min-width="180"><template #default="s"><code class="mono">{{ s.row.windowStart }}</code></template></el-table-column>
        <el-table-column label="严重度" width="112"><template #default="s"><span class="badge" :class="`sev-${(s.row.severity || 'LOW').toLowerCase()}`">{{ s.row.severity }}</span></template></el-table-column>
        <el-table-column label="状态" width="108"><template #default="s"><span class="badge" :class="statusClass(s.row.status)">{{ statusLabel(s.row.status) }}</span></template></el-table-column>
        <el-table-column label="风险分" width="128"><template #default="s"><div class="risk"><div class="risk__bar"><div class="risk__fill" :class="riskLevel(s.row.riskScore)" :style="{ width: s.row.riskScore + '%' }"></div></div><span class="risk__num">{{ s.row.riskScore }}</span></div></template></el-table-column>
        <el-table-column prop="hitCount" label="命中" width="72" />
        <el-table-column prop="logCount" label="日志" width="72" />
        <el-table-column label="规则" min-width="180"><template #default="s">{{ formatRules(s.row.ruleCodes) }}</template></el-table-column>
        <el-table-column label="操作" width="112"><template #default="s"><el-button link type="primary" @click="showDetail(s.row.id)">查看证据</el-button></template></el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="detailVisible" :title="`事件 #${detailId} 详情`" width="min(1200px, 95vw)" class="detail-dialog" @close="closeDetail">
      <div v-loading="detailLoading" class="detail-body">
      <el-alert v-if="detailError" type="error" :closable="false" title="事件详情加载失败">
        <el-button v-if="detailId !== null" link type="primary" @click="showDetail(detailId)">重试</el-button>
      </el-alert>
      <el-alert v-if="detail?.aiInjectionDetected" type="warning" :closable="false" title="检测到疑似 Prompt 注入，研判输入不包含原始日志文本。" />
      <template v-if="detail">
        <div class="detail-meta">
          <span class="badge" :class="`sev-${(detail.severity || 'LOW').toLowerCase()}`">{{ detail.severity }}</span>
          <span class="badge" :class="statusClass(detail.status)">{{ statusLabel(detail.status) }}</span>
          <code class="mono">{{ detail.clientIpMask }}</code>
          <span class="meta-sep">·</span>
          <span class="meta-text">风险分 {{ detail.riskScore }}</span>
          <span class="meta-sep">·</span>
          <span class="meta-text">命中 {{ detail.hitCount }} · 日志 {{ detail.logCount }}</span>
        </div>
        <div class="detail-actions">
          <el-button :loading="exporting" @click="download('report')">导出 Markdown</el-button>
          <el-button :loading="exporting" @click="download('pdf')">导出 PDF</el-button>
        </div>
        <el-tabs v-model="detailTab">
        <el-tab-pane label="命中证据" name="evidence">
        <div class="evidence-block">
          <div class="subheading"><h3>命中证据</h3><span class="count">{{ detail.evidence.length }} 条</span></div>
          <el-table :data="detail.evidence" empty-text="暂无证据">
            <el-table-column label="日志 ID" width="110"><template #default="s"><el-button link type="primary" @click="showContext(s.row.logId)"><code class="mono">{{ s.row.logId }}</code></el-button></template></el-table-column>
            <el-table-column label="规则名称" min-width="150"><template #default="s">{{ formatRule(s.row.ruleCode) }}</template></el-table-column>
            <el-table-column prop="matchedField" label="命中字段" width="120" />
            <el-table-column prop="evidenceSnippet" label="证据片段" min-width="200" show-overflow-tooltip />
            <el-table-column prop="rawLineMasked" label="原始行（脱敏）" min-width="300" show-overflow-tooltip />
          </el-table>
        </div>
        </el-tab-pane>
        <el-tab-pane label="人工处置" name="response">
        <div class="workflow">
          <h3>人工处置</h3>
          <p>备注将写入时间线，请勿填写敏感信息。</p>
          <el-select v-model="selectedStatus" aria-label="事件状态" style="width: 180px">
            <el-option v-for="item in statuses" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-input v-model="note" type="textarea" :rows="2" maxlength="2000" show-word-limit placeholder="填写处置备注" />
          <el-button type="primary" :loading="saving" @click="saveStatus">保存状态和备注</el-button>
          <h3>事件时间线</h3>
          <div v-loading="timelineLoading">
            <p v-if="!timeline.length && !timelineLoading">暂无处置记录</p>
            <div v-for="item in timeline" :key="item.id" class="timeline-item">
              <strong>{{ item.createdAt }} · {{ item.action }}</strong>
              <div>{{ item.oldStatus || '—' }} → {{ item.newStatus || '—' }} · 操作人：{{ item.actor || '未知' }}</div>
              <div class="note">{{ item.note || '无备注' }}</div>
            </div>
          </div>
        </div>
        </el-tab-pane>
        <el-tab-pane label="AI 研判" name="analysis">
      <div class="analysis">
        <div class="analysis-heading"><h2>AI 研判历史</h2><el-button type="primary" :loading="analyzing" @click="analyze">{{ analyzing ? '研判中…' : '发起 AI 研判' }}</el-button></div>
        <div v-loading="historyLoading">
          <el-alert v-if="historyError" type="error" :closable="false" title="研判历史加载失败">
            <el-button link type="primary" @click="changeHistoryPage(historyPage)">重试</el-button>
          </el-alert>
          <el-table v-else :data="history" empty-text="暂无研判历史">
            <el-table-column label="研判 ID" width="100"><template #default="s"><code class="mono">{{ s.row.id }}</code></template></el-table-column>
            <el-table-column prop="createdAt" label="创建时间" min-width="170" />
            <el-table-column prop="requestedBy" label="发起人" min-width="110" />
            <el-table-column prop="status" label="状态" min-width="100" />
            <el-table-column prop="source" label="来源" min-width="140" />
            <el-table-column prop="cacheStatus" label="缓存" min-width="100" />
            <el-table-column label="操作" width="90"><template #default="s"><el-button link type="primary" @click="selectAnalysis(s.row)">查看详情</el-button></template></el-table-column>
          </el-table>
          <el-pagination v-if="historyTotal > 0" :current-page="historyPage" :page-size="historySize" :total="historyTotal" layout="total, prev, pager, next" @current-change="changeHistoryPage" />
        </div>
        <div v-loading="latestLoading">
          <el-alert v-if="latestError" type="error" :closable="false" title="最新研判加载失败">
            <el-button link type="primary" @click="loadLatest(detail.id, selectionVersion)">重试</el-button>
          </el-alert>
          <p v-else-if="!analysis && !latestLoading" class="empty">暂无研判结果，请发起 AI 研判。</p>
          <template v-if="analysis">
        <div class="verdict">
          <div class="verdict__head">
            <span class="verdict__badge" :class="analysis.source === 'RULE_FALLBACK' ? 'vb-fallback' : 'vb-model'">{{ analysis.source === 'RULE_FALLBACK' ? '规则降级' : '模型研判' }}</span>
            <code class="mono">#{{ analysis.id }}</code>
            <span v-if="analysis.cacheStatus === 'CACHED'" class="verdict__cache">缓存命中 · 新增 token 0</span>
          </div>
          <div class="verdict__conf">置信度 <div class="conf-bar"><div class="conf-fill" :style="{ width: ((analysis.confidence ?? 0) * 100) + '%' }"></div></div><span>{{ analysis.confidence == null ? '未提供' : Math.round((analysis.confidence ?? 0) * 100) + '%' }}</span></div>
        </div>
        <el-alert v-if="analysis.cacheStatus === 'CACHED'" type="success" :closable="false" :title="`命中缓存，复用研判 #${analysis.cacheSourceId}`" />
        <el-alert v-if="analysis.source === 'RULE_FALLBACK'" type="warning" :closable="false" title="模型研判不可用，以下为规则降级结果，并非模型结论。" />
        <div class="summary-card"><div class="summary-card__label">事件总结</div><p>{{ analysis.summary }}</p></div>
        <h3>攻击链</h3>
        <div class="chain">
          <div v-for="(step, index) in analysis.attackChain" :key="index" class="chain__step">
            <div class="chain__stage">{{ step.stage }}</div>
            <div class="chain__desc">{{ step.description }}</div>
            <div class="chain__ids"><code class="mono" v-for="lid in step.logIds" :key="lid">#{{ lid }}</code></div>
          </div>
        </div>
        <h3>处置建议</h3>
        <ul class="recs"><li v-for="(item, index) in analysis.recommendations" :key="index">{{ item }}</li></ul>
        <div class="fp-note"><strong>误报提示：</strong>{{ analysis.falsePositiveHint }}</div>
        <p class="meta-line">创建 {{ analysis.createdAt || '—' }} · {{ analysis.requestedBy || '未知' }} · 模型 {{ analysis.model || '—' }} · 耗时 {{ analysis.costMs ?? '—' }} ms · token {{ analysis.promptTokens ?? '—' }}/{{ analysis.completionTokens ?? '—' }}</p>
          </template>
        </div>
      </div>
        </el-tab-pane>
        </el-tabs>
      </template>
      </div>
    </el-dialog>

    <el-dialog v-model="contextVisible" title="证据日志上下文" width="min(1000px, 95vw)" append-to-body>
      <div v-loading="contextLoading">
        <p v-if="context" class="hint">批次 #{{ context.batchId }} · 当前日志 #{{ context.targetLogId }}。展示同批次相邻的已解析行。</p>
        <div v-if="context" class="context-list">
          <div v-for="line in context.lines" :key="line.logId" class="context-line" :class="{ current: line.isCurrent }">
            <div class="context-line__head">行 {{ line.lineNo }} · <code class="mono">#{{ line.logId }}</code> · <code class="mono">{{ line.logTime }}</code> <strong v-if="line.isCurrent">（当前证据）</strong></div>
            <pre>{{ line.rawLineMasked }}</pre>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.events { max-width: 1500px; min-width: 0; margin: auto; color: var(--csys-text); }
.page-header { padding: 0 0 18px; border-bottom: 1px solid var(--csys-border-subtle); }
.kicker { display: block; color: var(--csys-faint); font-size: 11px; font-weight: 600; letter-spacing: .12em; text-transform: uppercase; }
h1 { margin: 8px 0 0; color: var(--csys-text); font-size: 26px; letter-spacing: -.02em; }
h2, h3 { color: var(--csys-text); }
h2 { margin: 0; font-size: 14px; font-weight: 600; }
h3 { margin: 14px 0 10px; font-size: 13px; font-weight: 600; }
p { margin: 6px 0; color: var(--csys-muted); font-size: 13px; line-height: 1.6; }
.panel { min-width: 0; margin-top: 16px; padding: 18px 20px; background: var(--csys-surface); border: 1px solid var(--csys-border-subtle); border-radius: 8px; }
.panel__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.count { color: var(--csys-faint); font-weight: 400; font-variant-numeric: tabular-nums; }
.scope { display: flex; align-items: center; gap: 8px; color: var(--csys-muted); font-size: 12px; }
.detection { border-left: 2px solid var(--csys-accent); }
.detect-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.hint { font-size: 12px; color: var(--csys-faint); margin-top: 12px; }
.filters { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.controls, .detail-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.detail-body { min-height: 100px; max-height: 74vh; overflow: auto; }
.detail-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; font-size: 13px; }
.detail-meta code { color: var(--csys-text); }
.meta-sep { color: var(--csys-faint); }
.meta-text { color: var(--csys-muted); }
.detail-actions { margin: 0 0 8px; }
.evidence-block { min-width: 0; margin: 8px 0; }
.subheading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.subheading h3 { margin: 0; }
.analysis-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.workflow, .analysis { min-width: 0; padding: 4px 0; overflow-wrap: anywhere; }
.workflow .el-textarea { display: block; max-width: 720px; margin: 12px 0; }
.workflow h3:not(:first-child) { margin-top: 20px; }
.timeline-item { padding: 10px 0 10px 14px; border-left: 2px solid var(--csys-border); color: var(--csys-muted); font-size: 12px; line-height: 1.7; }
.timeline-item strong { color: var(--csys-text); }
.note { white-space: pre-wrap; overflow-wrap: anywhere; }
.empty { color: var(--csys-faint); font-size: 13px; }
/* 研判结果 */
.verdict { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 14px; background: var(--csys-surface-2); border: 1px solid var(--csys-border-subtle); border-radius: 8px; margin: 4px 0 12px; }
.verdict__head { display: flex; align-items: center; gap: 10px; }
.verdict__badge { padding: 3px 9px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.vb-model { color: var(--csys-accent); background: var(--csys-accent-soft); }
.vb-fallback { color: var(--csys-high); background: rgba(240,136,62,.12); }
.verdict__cache { color: var(--csys-success); font-size: 12px; }
.verdict__conf { display: flex; align-items: center; gap: 8px; color: var(--csys-muted); font-size: 12px; font-variant-numeric: tabular-nums; }
.conf-bar { width: 120px; height: 5px; background: var(--csys-border); border-radius: 3px; overflow: hidden; }
.conf-fill { height: 100%; background: var(--csys-accent); border-radius: 3px; }
.summary-card { padding: 12px 14px; background: var(--csys-surface-2); border-left: 2px solid var(--csys-accent); border-radius: 4px; margin: 8px 0; }
.summary-card__label { font-size: 11px; color: var(--csys-faint); text-transform: uppercase; letter-spacing: .1em; margin-bottom: 6px; }
.summary-card p { color: var(--csys-text); margin: 0; font-size: 13px; white-space: pre-wrap; }
.analysis h3 { margin-top: 18px; }
.chain { display: flex; flex-direction: column; gap: 8px; }
.chain__step { padding: 10px 12px; background: var(--csys-surface-2); border-radius: 6px; border: 1px solid var(--csys-border-subtle); }
.chain__stage { display: inline-block; font-size: 11px; font-weight: 600; color: var(--csys-accent); background: var(--csys-accent-soft); padding: 2px 8px; border-radius: 4px; margin-bottom: 6px; }
.chain__desc { color: var(--csys-text); font-size: 13px; line-height: 1.6; }
.chain__ids { margin-top: 6px; display: flex; gap: 6px; flex-wrap: wrap; }
.chain__ids code { font-size: 11px; color: var(--csys-muted); }
.recs { margin: 8px 0; padding-left: 20px; }
.recs li { color: var(--csys-text); font-size: 13px; line-height: 1.7; margin-bottom: 4px; }
.fp-note { padding: 8px 12px; background: rgba(210,153,34,.08); border-radius: 4px; font-size: 12px; color: var(--csys-muted); margin: 10px 0; }
.fp-note strong { color: var(--csys-medium); }
.meta-line { font-size: 11px; color: var(--csys-faint); margin-top: 12px; font-variant-numeric: tabular-nums; }
/* 徽章 */
.badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; white-space: nowrap; }
.sev-critical { color: var(--csys-critical); background: var(--csys-critical-soft); }
.sev-high { color: var(--csys-high); background: var(--csys-high-soft); }
.sev-medium { color: var(--csys-medium); background: var(--csys-medium-soft); }
.sev-low { color: var(--csys-low); background: var(--csys-low-soft); }
.badge.st-new { color: var(--csys-muted); background: var(--csys-hover); }
.badge.st-investigating { color: var(--csys-accent); background: var(--csys-accent-soft); }
.badge.st-confirmed { color: var(--csys-critical); background: var(--csys-critical-soft); }
.badge.st-false { color: var(--csys-success); background: var(--csys-success-soft); }
.badge.st-closed { color: var(--csys-faint); background: var(--csys-hover); }
/* 风险分 */
.risk { display: flex; align-items: center; gap: 8px; }
.risk__bar { width: 60px; height: 4px; background: var(--csys-border); border-radius: 2px; overflow: hidden; }
.risk__fill { height: 100%; border-radius: 2px; }
.risk__num { font-size: 12px; color: var(--csys-muted); font-variant-numeric: tabular-nums; }
.risk-high { background: var(--csys-critical); }
.risk-mid { background: var(--csys-high); }
.risk-low { background: var(--csys-medium); }
.risk-min { background: var(--csys-low); }
.el-alert { margin-bottom: 12px; }
.context-list { max-height: 65vh; overflow: auto; display: flex; flex-direction: column; gap: 4px; }
.context-line { padding: 8px 10px; border-left: 2px solid transparent; color: var(--csys-muted); font-size: 12px; border-radius: 0 4px 4px 0; }
.context-line__head { color: var(--csys-faint); }
.context-line.current { background: var(--csys-surface-2); border-left-color: var(--csys-accent); color: var(--csys-text); }
.context-line.current .context-line__head { color: var(--csys-text); }
.context-line pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 4px 0 0; font-size: 12px; color: var(--csys-muted); }
@media (max-width: 600px) {
  .panel { padding: 14px; }
  .detect-row { align-items: stretch; }
  .detect-row :deep(.el-input-number) { width: 100%; }
  .detect-row :deep(.el-button) { flex: 1 1 120px; margin-left: 0; }
  .workflow :deep(.el-select) { max-width: 100%; }
}
</style>
