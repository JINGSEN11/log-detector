import { http } from './request'

// ---------- 通用类型 ----------
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

// ---------- 管理员审计 ----------
export interface AuditEntry {
  id: number
  actorUserId?: number | null
  action: string
  targetType?: string | null
  targetId?: string | null
  traceId?: string | null
  createdAt?: string | null
}

export const auditApi = {
  list: (page = 1, size = 20) => http.get<PageResult<AuditEntry>>('/audit', { page, size })
}

// ---------- 认证 ----------
export interface LoginResponse {
  token: string
  username: string
  role: string
}

export interface UserInfo {
  id: number
  username: string
  role: string
}

export const authApi = {
  login: (username: string, password: string) =>
    http.post<LoginResponse>('/auth/login', { username, password }),
  me: () => http.get<UserInfo>('/auth/me')
}

// ---------- 示例 CRUD ----------
export interface DemoItem {
  id?: number
  name: string
  description?: string
  status?: string
  createdAt?: string
}

export const demoApi = {
  page: (page = 1, size = 10, keyword = '') =>
    http.get<PageResult<DemoItem>>('/demo/items', { page, size, keyword }),
  create: (payload: DemoItem) => http.post<DemoItem>('/demo/items', payload),
  update: (id: number, payload: DemoItem) => http.put<DemoItem>(`/demo/items/${id}`, payload),
  remove: (id: number) => http.delete<void>(`/demo/items/${id}`)
}

// ---------- 日志导入与场景 ----------
export type LogImportFormat = 'auto' | 'custom'

export interface LogScenario {
  id: string
  name: string
  description: string
}

export interface LogImportResult {
  batchId: number
  contentSha256: string
  status: string
  totalLines: number
  parsedLines: number
  skippedLines: number
  preview: Array<{
    lineNo: number
    pseudonymousIp: string
    maskedIp: string
    timestamp: string
    method: string
    path: string
    status: number
    referer: string
    userAgent: string
    rawLine: string
  }>
}

export interface ScenarioPreview {
  name: string
  lines: string[]
  seed: number
  baseTime: number
  count: number
}

export interface LogEntryPage {
  batchId: number
  page: number
  pageSize: number
  total: number
  items: LogImportResult['preview']
}

export const logApi = {
  batchEntries: (batchId: number, page: number, pageSize = 100) =>
    http.get<LogEntryPage>(`/logs/batches/${batchId}/entries`, { page, pageSize }),
  scenarios: () => http.get<LogScenario[]>('/logs/scenarios'),
  scenarioPreview: (id: string) => http.get<ScenarioPreview>(`/logs/scenarios/${id}/preview`),
  replayScenario: (batchId: number) => http.get<ScenarioPreview>(`/logs/scenarios/batches/${batchId}/replay`),
  importScenario: (id: string, preview: ScenarioPreview) => http.post<LogImportResult>(
    `/logs/scenarios/${id}/import`, undefined,
    { params: { seed: preview.seed, baseTime: preview.baseTime, count: preview.count } }
  ),
  importFile: (file: File, format: LogImportFormat = 'auto') => {
    const body = new FormData()
    body.append('file', file)
    body.append('format', format)
    return http.post<LogImportResult>('/logs/import', body)
  }
}

// ---------- 安全概览 ----------
export interface DashboardData {
  eventCount: number
  highSeverityEventCount: number
  logCount: number
  aiAnalysisCount: number
  trend24h: Array<{ hour: string; eventCount: number }>
  severityDistribution: Array<{ severity: string; count: number }>
  topSources: Array<{ clientIpMask: string; eventCount: number }>
  latestEvents: Array<{ id: number; clientIpMask: string; windowStart: string; severity: string; riskScore: number; status: string }>
}
export interface LogContext {
  batchId: number
  targetLogId: number
  lines: Array<{ logId: number; lineNo: number; logTime: string; rawLineMasked: string; isCurrent: boolean }>
}
export interface AnalysisCost {
  requestCount: number
  cacheHits: number
  cacheHitRate: number | null
  savedTokensEstimate: number
  callCount: number
  promptTokens: number
  completionTokens: number
  averageLatencyMs: number | null
  unknownTokenCalls: number
  recentCalls: Array<{ id: number; eventId: number; model: string; outcome: string; elapsedMs: number; promptTokens: number | null; completionTokens: number | null; createdAt: string }>
}
export const dashboardApi = {
  overview: () => http.get<DashboardData>('/dashboard'),
  analysisCost: () => http.get<AnalysisCost>('/analysis-cost'),
  context: (logId: number) => http.get<LogContext>(`/logs/${logId}/context`)
}

// ---------- 规则检测与事件 ----------
export interface DetectionEvent {
  id: number
  clientIpMask: string
  windowStart: string
  windowEnd: string
  riskScore: number
  severity: string
  status: string
  hitCount: number
  logCount: number
  ruleCodes: string[]
  aiInjectionDetected: boolean
}

export interface EventEvidence {
  logId: number
  ruleCode: string
  matchedField: string
  evidenceSnippet: string
  severity: string
  logTime: string
  rawLineMasked: string
}

export interface EventAnalysis {
  id: number
  createdAt: string
  requestedBy: string | null
  status: string
  source: 'MODEL' | 'RULE_FALLBACK'
  cacheStatus: 'MISS' | 'CACHED' | 'LEGACY'
  cacheSourceId: number | null
  summary: string
  attackChain: Array<{ stage: string; description: string; logIds: number[] }>
  recommendations: string[]
  confidence: number | null
  falsePositiveHint: string
  model: string | null
  costMs: number | null
  promptTokens: number | null
  completionTokens: number | null
}

export type EventStatus = 'NEW' | 'INVESTIGATING' | 'CONFIRMED' | 'FALSE_POSITIVE' | 'CLOSED'
export interface EventTimelineItem {
  id: number
  action: string
  oldStatus: string | null
  newStatus: string | null
  note: string | null
  createdAt: string
  actor: string | null
}

export const eventApi = {
  updateStatus: (id: number, status: EventStatus, note: string) => http.post<DetectionEvent & { evidence: EventEvidence[] }>(`/events/${id}/status`, { status, note }),
  timeline: (id: number) => http.get<EventTimelineItem[]>(`/events/${id}/timeline`),
  exportCsv: (batchId?: number) => http.download('/events/export.csv', { batchId }),
  exportReport: (id: number) => http.download(`/events/${id}/report.md`),
  exportPdf: (id: number) => http.download(`/events/${id}/report.pdf`),
  analyze: (id: number) => http.post<EventAnalysis>(`/events/${id}/analysis`, undefined, { timeout: 150000 }),
  latestAnalysis: (id: number) => http.get<EventAnalysis | null>(`/events/${id}/analyses/latest`),
  analyses: (id: number, page = 1, size = 10) => http.get<{ records: EventAnalysis[]; total: number; page: number; size: number }>(`/events/${id}/analyses`, { page, size }),
  detect: (batchId: number) => http.post<{ batchId: number; hitCount: number; eventCount: number }>(`/detection/batches/${batchId}`),
  list: (batchId?: number) => http.get<DetectionEvent[]>('/events', { batchId }),
  detail: (id: number) => http.get<DetectionEvent & { evidence: EventEvidence[] }>(`/events/${id}`)
}

// ---------- 大模型 ----------
export interface LlmChatResponse {
  content: string
  model: string
  costMs: number
  promptTokens: number
  completionTokens: number
}

export interface LlmConfig {
  model: string
  mock: boolean
  baseUrl: string
  apiKeyConfigured: boolean
  timeoutSeconds: number
}

export const llmApi = {
  chat: (prompt: string, timeoutSeconds = 60) =>
    http.post<LlmChatResponse>('/llm/chat', { prompt }, { timeout: (timeoutSeconds + 15) * 1000 }),
  config: () => http.get<LlmConfig>('/llm/config')
}
