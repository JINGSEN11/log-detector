<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
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
    // 请求层负责提示错误；清空列表以免旧结果与当前筛选条件不一致。
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
        <span class="eyebrow">SECURITY / EVENTS</span>
        <h1>规则检测与事件</h1>
      </div>
    </header>
    <section class="panel detection">
      <div class="section-heading">
        <div><span class="section-index">01 / DETECTION</span><h2>运行规则检测</h2><p>请输入已导入的批次 ID。</p></div>
      </div>
      <div class="controls">
      <el-input-number v-model="batchId" :min="1" :precision="0" placeholder="批次 ID" aria-label="批次 ID" />
      <el-button type="primary" :loading="detecting" @click="detect">开始检测</el-button>
      <el-button @click="showBatchEvents">查看该批次事件</el-button>
      <el-button :loading="loading" @click="loadEvents">刷新事件</el-button>
      <el-button :loading="exporting" @click="download('csv')">导出事件 CSV</el-button>
      </div>
    </section>
    <section class="panel event-list">
      <div class="section-heading">
        <div><span class="section-index">02 / EVENT QUEUE</span><h2>攻击事件</h2><p>{{ activeBatchId ? `当前范围：批次 #${activeBatchId}` : '当前范围：全部批次' }} · 列表最多显示最新 500 个事件，CSV 导出当前范围的全部事件。</p></div>
        <div class="controls"><span class="count">当前显示 {{ events.length }} 个事件</span><el-button v-if="activeBatchId" @click="showAllEvents">查看全部事件</el-button></div>
      </div>
      <el-alert v-if="eventsError" type="error" :closable="false" title="事件加载失败，请刷新重试" />
      <el-table v-loading="loading" :data="events" :empty-text="eventsError ? '事件加载失败' : activeBatchId ? '该批次暂无事件' : '暂无事件，请先检测日志批次'">
        <el-table-column prop="id" label="事件 ID" width="90" />
        <el-table-column prop="clientIpMask" label="来源 IP（掩码）" min-width="140" />
        <el-table-column prop="windowStart" label="开始时间" min-width="170" />
        <el-table-column prop="windowEnd" label="结束时间" min-width="170" />
        <el-table-column prop="severity" label="严重度" width="100" />
        <el-table-column prop="status" label="状态" min-width="140" />
        <el-table-column prop="riskScore" label="风险分" width="90" />
        <el-table-column prop="hitCount" label="命中数" width="90" />
        <el-table-column prop="logCount" label="日志数" width="90" />
        <el-table-column label="规则" min-width="200"><template #default="scope">{{ formatRules(scope.row.ruleCodes) }}</template></el-table-column>
        <el-table-column label="操作" width="90"><template #default="scope"><el-button link type="primary" @click="showDetail(scope.row.id)">查看证据</el-button></template></el-table-column>
      </el-table>
    </section>
    <el-dialog v-model="detailVisible" :title="`事件 #${detailId} 详情`" width="min(1200px, 95vw)" class="detail-dialog" @close="closeDetail">
      <div v-loading="detailLoading" class="detail-body">
      <el-alert v-if="detailError" type="error" :closable="false" title="事件详情加载失败">
        <el-button v-if="detailId !== null" link type="primary" @click="showDetail(detailId)">重试</el-button>
      </el-alert>
      <el-alert v-if="detail?.aiInjectionDetected" type="warning" :closable="false" title="检测到疑似 Prompt 注入，研判输入不包含原始日志文本。" />
      <template v-if="detail">
        <div class="detail-actions">
          <el-button :loading="exporting" @click="download('report')">导出 Markdown 报告</el-button>
          <el-button :loading="exporting" @click="download('pdf')">导出 PDF 报告</el-button>
        </div>
        <el-tabs v-model="detailTab">
        <el-tab-pane label="命中证据" name="evidence">
        <div class="evidence-block">
          <div class="subheading"><h3>命中证据</h3><span class="count">{{ detail.evidence.length }} 条</span></div>
          <el-table :data="detail.evidence" empty-text="暂无证据">
            <el-table-column label="日志 ID" width="110"><template #default="scope"><el-button link type="primary" @click="showContext(scope.row.logId)">{{ scope.row.logId }}</el-button></template></el-table-column>
            <el-table-column label="规则名称" min-width="150"><template #default="scope">{{ formatRule(scope.row.ruleCode) }}</template></el-table-column>
            <el-table-column prop="matchedField" label="命中字段" width="120" />
            <el-table-column prop="evidenceSnippet" label="证据片段" min-width="200" show-overflow-tooltip />
            <el-table-column prop="rawLineMasked" label="原始行（脱敏）" min-width="300" show-overflow-tooltip />
          </el-table>
        </div>
        </el-tab-pane>
        <el-tab-pane label="人工处置" name="response">
        <div class="workflow">
          <h3>人工处置</h3>
          <p>当前状态：{{ detail.status }}。备注将写入时间线，请勿填写敏感信息。</p>
          <el-select v-model="selectedStatus" aria-label="事件状态" style="width: 180px">
            <el-option v-for="item in statuses" :key="item.value" :label="`${item.label} (${item.value})`" :value="item.value" />
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
        <div class="analysis-heading"><h2>AI 研判历史</h2><el-button type="primary" :loading="analyzing" @click="analyze">发起 AI 研判</el-button></div>
        <div v-loading="historyLoading">
          <el-alert v-if="historyError" type="error" :closable="false" title="研判历史加载失败">
            <el-button link type="primary" @click="changeHistoryPage(historyPage)">重试</el-button>
          </el-alert>
          <el-table v-else :data="history" empty-text="暂无研判历史">
            <el-table-column prop="id" label="研判 ID" width="100" />
            <el-table-column prop="createdAt" label="创建时间" min-width="170" />
            <el-table-column prop="requestedBy" label="发起人" min-width="120" />
            <el-table-column prop="status" label="状态" min-width="100" />
            <el-table-column prop="source" label="来源" min-width="140" />
            <el-table-column prop="cacheStatus" label="缓存" min-width="100" />
            <el-table-column label="操作" width="90"><template #default="scope"><el-button link type="primary" @click="selectAnalysis(scope.row)">查看详情</el-button></template></el-table-column>
          </el-table>
          <el-pagination v-if="historyTotal > 0" :current-page="historyPage" :page-size="historySize" :total="historyTotal" layout="total, prev, pager, next" @current-change="changeHistoryPage" />
        </div>
        <div v-loading="latestLoading">
          <el-alert v-if="latestError" type="error" :closable="false" title="最新研判加载失败">
            <el-button link type="primary" @click="loadLatest(detail.id, selectionVersion)">重试</el-button>
          </el-alert>
          <p v-else-if="!analysis && !latestLoading">暂无研判结果，请发起 AI 研判。</p>
          <template v-if="analysis">
        <h2>研判结果 #{{ analysis.id }} · {{ analysis.source }} · {{ analysis.cacheStatus }}</h2>
        <p v-if="analysis.cacheStatus === 'CACHED'">命中缓存，复用研判 #{{ analysis.cacheSourceId }}；本次新增 token 为 0。</p>
        <p>创建时间：{{ analysis.createdAt || '—' }} · 发起人：{{ analysis.requestedBy || '未知' }} · 状态：{{ analysis.status || '—' }}</p>
        <el-alert v-if="analysis.source === 'RULE_FALLBACK'" type="warning" :closable="false" title="模型研判不可用，以下为规则降级结果，并非模型结论。" />
        <p><strong>事件总结：</strong>{{ analysis.summary }}</p>
        <p><strong>置信度：</strong>{{ analysis.confidence == null ? '未提供' : analysis.confidence }}</p>
        <h3>攻击链</h3>
        <div v-for="(step, index) in analysis.attackChain" :key="index" class="chain-step">
          <strong>{{ step.stage }}</strong>：{{ step.description }}（日志 ID：{{ step.logIds.join(', ') }}）
        </div>
        <h3>处置建议</h3>
        <ul><li v-for="(item, index) in analysis.recommendations" :key="index">{{ item }}</li></ul>
        <p><strong>误报提示：</strong>{{ analysis.falsePositiveHint }}</p>
        <p>模型：{{ analysis.model || '—' }} · 耗时：{{ analysis.costMs ?? '—' }} ms · 输入 token：{{ analysis.promptTokens ?? '—' }} · 输出 token：{{ analysis.completionTokens ?? '—' }}</p>
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
        <p v-if="context">批次 #{{ context.batchId }} · 当前日志 #{{ context.targetLogId }}。展示同批次相邻的已解析行。</p>
        <div v-if="context" class="context-list">
          <div v-for="line in context.lines" :key="line.logId" class="context-line" :class="{ current: line.isCurrent }">
            <div>行 {{ line.lineNo }} · 日志 #{{ line.logId }} · {{ line.logTime }} <strong v-if="line.isCurrent">（当前证据）</strong></div>
            <pre>{{ line.rawLineMasked }}</pre>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.events { max-width: 1500px; min-width: 0; margin: auto; color: #23343d; }
.page-header { padding: 4px 0 18px; border-bottom: 2px solid #294868; }
.eyebrow, .section-index { display: block; color: #526f8d; font-size: 11px; font-weight: 700; letter-spacing: .12em; line-height: 1.5; }
h1 { margin: 8px 0 6px; color: #172d35; font-size: 26px; letter-spacing: -.02em; }
h2, h3 { color: #20363e; }
h2 { margin: 5px 0; font-size: 17px; }
h3 { margin: 5px 0 10px; font-size: 15px; }
p { margin: 6px 0; color: #657780; font-size: 13px; line-height: 1.6; }
.panel { min-width: 0; margin-top: 16px; padding: 22px; background: #fff; border: 1px solid #dce5e6; border-radius: 4px; }
.section-heading, .subheading { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.count { flex: none; color: #667b80; font-size: 12px; font-variant-numeric: tabular-nums; }
.detection { border-left: 3px solid #426d98; }
.controls, .detail-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.controls :deep(.el-button + .el-button), .detail-actions :deep(.el-button + .el-button) { margin-left: 0; }
.detail-body { min-height: 100px; max-height: 72vh; overflow: auto; }
.detail-actions { margin: 0 0 12px; }
.evidence-block { min-width: 0; margin: 12px 0; }
.analysis-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.analysis-heading h2 { margin: 0; }
.subheading { margin-bottom: 12px; }
.workflow, .analysis { min-width: 0; padding: 12px 0; overflow-wrap: anywhere; }
.workflow .el-textarea { display: block; max-width: 720px; margin: 12px 0; }
.workflow h3:not(:first-child) { margin-top: 20px; }
.timeline-item { padding: 12px 0 12px 14px; border-left: 2px solid #d6e1ec; color: #52676d; font-size: 13px; line-height: 1.7; }
.timeline-item strong { color: #263e45; }
.note { white-space: pre-wrap; overflow-wrap: anywhere; }
.analysis p, .analysis li, .chain-step { color: #344b51; font-size: 14px; line-height: 1.7; white-space: pre-wrap; }
.analysis h2 { margin: 6px 0 16px; }
.analysis h3 { margin-top: 20px; }
.analysis ul { padding-left: 22px; }
.analysis :deep(.el-pagination) { margin: 14px 0 22px; flex-wrap: wrap; }
.el-alert { margin-bottom: 12px; }
.context-list { max-height: 65vh; overflow: auto; }
.context-line { padding: 10px; border-left: 3px solid transparent; color: #52676d; font-size: 12px; }
.context-line.current { background: #edf3f9; border-left-color: #426d98; color: #20363e; }
.context-line pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 6px 0 0; }
@media (max-width: 600px) {
  .panel { padding: 16px; }
  .section-heading { align-items: flex-start; }
  .controls, .detail-actions { align-items: stretch; }
  .controls :deep(.el-input-number) { width: 100%; }
  .controls :deep(.el-button), .detail-actions :deep(.el-button) { flex: 1 1 140px; }
  .workflow :deep(.el-select) { max-width: 100%; }
}
</style>
