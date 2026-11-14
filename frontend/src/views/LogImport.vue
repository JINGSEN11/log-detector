<script setup lang="ts">
import { ref } from 'vue'
import type { UploadFile, UploadInstance } from 'element-plus'
import { ElMessage } from 'element-plus'
import { logApi, type LogImportFormat, type LogImportResult } from '../api'
const selectedFile = ref<File | null>(null)
const selectedFormat = ref<LogImportFormat>('auto')
const result = ref<LogImportResult | null>(null)
const entries = ref<LogImportResult['preview']>([])
const entryPage = ref(1)
const entriesLoading = ref(false)
const rawLineVisible = ref(false)
const selectedRawLine = ref('')
const selectedLineNo = ref<number | null>(null)
let entryRequest = 0

function showRawLine(line: LogImportResult['preview'][number]): void {
  selectedLineNo.value = line.lineNo
  selectedRawLine.value = line.rawLine || ''
  rawLineVisible.value = true
}
const loading = ref(false)
const uploadRef = ref<UploadInstance>()

async function loadEntries(page: number): Promise<void> {
  if (!result.value) return
  const batchId = result.value.batchId
  const request = ++entryRequest
  entryPage.value = page
  entriesLoading.value = true
  try {
    const response = await logApi.batchEntries(batchId, page)
    if (request === entryRequest && result.value?.batchId === batchId) entries.value = response.items
  } catch {
    if (request === entryRequest) entries.value = []
  } finally {
    if (request === entryRequest) entriesLoading.value = false
  }
}

function chooseFile(file: UploadFile): void {
  if (!file.raw) return
  const raw = file.raw
  let error = ''
  if (!/\.(log|txt|json|jsonl)$/i.test(raw.name)) error = '仅支持 .log、.txt、.json 和 .jsonl 文件'
  else if (raw.size === 0) error = '不能上传空文件'
  else if (raw.size > 10 * 1024 * 1024) error = '文件大小不能超过 10 MB'
  if (error) {
    selectedFile.value = null
    uploadRef.value?.clearFiles()
    ElMessage.error(error)
  } else {
    selectedFile.value = raw
  }
  result.value = null
}

async function importSelectedFile(): Promise<void> {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择日志文件')
    return
  }
  loading.value = true
  try {
    result.value = await logApi.importFile(selectedFile.value, selectedFormat.value)
    entryPage.value = 1
    entries.value = result.value.preview
    void loadEntries(1)
    ElMessage.success(`导入批次 #${result.value.batchId} 已写入数据库`)
  } catch {
    result.value = null
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="log-import">
    <header class="page-header">
      <span class="kicker">WORKSPACE / LOG IMPORT</span>
      <h1>日志导入</h1>
    </header>

    <section class="panel">
      <div class="panel__head"><h2>上传日志</h2></div>
      <div class="format-row">
        <label for="log-format">日志格式</label>
        <el-select id="log-format" v-model="selectedFormat" aria-label="日志格式" :disabled="loading" style="width: 200px">
          <el-option label="自动（按扩展名）" value="auto" />
          <el-option label="自定义 TSV" value="custom" />
        </el-select>
        <span v-if="selectedFormat === 'auto'" class="hint">.log / .txt 为访问日志，.json / .jsonl 为逐行 JSON</span>
      </div>
      <el-upload ref="uploadRef" class="upload" drag :auto-upload="false" :show-file-list="true" accept=".log,.txt,.json,.jsonl"
        :limit="1" :on-change="chooseFile" :on-remove="() => { selectedFile = null; result = null }">
        <el-icon class="el-icon--upload"><upload-filled /></el-icon>
        <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择</em></div>
        <template #tip><div class="upload-tip">支持 .log / .txt / .json / .jsonl，单文件不超过 10 MB、30000 行。</div></template>
      </el-upload>
      <el-button class="action" type="primary" :loading="loading" :disabled="!selectedFile" @click="importSelectedFile">解析并导入</el-button>
    </section>

    <section v-if="result" class="panel">
      <div class="panel__head">
        <h2>导入结果</h2>
        <div class="batch-badge">批次 <code class="mono batch-id">#{{ result.batchId }}</code></div>
      </div>
      <div class="stats">
        <div class="stat"><span>批次状态</span><strong>{{ result.status }}</strong></div>
        <div class="stat"><span>总行数</span><strong class="mono">{{ result.totalLines }}</strong></div>
        <div class="stat"><span>解析成功</span><strong class="mono ok">{{ result.parsedLines }}</strong></div>
        <div class="stat"><span>跳过行数</span><strong class="mono skip">{{ result.skippedLines }}</strong></div>
      </div>
      <p class="hint">批次 ID 可在「规则检测与事件」页用于运行检测；点击「查看原始行」查看完整脱敏内容。</p>
      <el-table v-loading="entriesLoading" :data="entries" class="table" max-height="420" scrollbar-always-on>
        <el-table-column label="行号" width="72"><template #default="s"><code class="mono">{{ s.row.lineNo }}</code></template></el-table-column>
        <el-table-column label="来源 IP" width="145"><template #default="s"><code class="mono">{{ s.row.maskedIp }}</code></template></el-table-column>
        <el-table-column prop="method" label="方法" width="72" />
        <el-table-column prop="path" label="路径" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态码" width="80"><template #default="s"><code class="mono">{{ s.row.status }}</code></template></el-table-column>
        <el-table-column prop="userAgent" label="User-Agent" min-width="180" show-overflow-tooltip />
        <el-table-column prop="rawLine" label="原始行" min-width="900" show-overflow-tooltip />
        <el-table-column label="操作" width="112" fixed="right">
          <template #default="s"><el-button link type="primary" @click="showRawLine(s.row)">查看原始行</el-button></template>
        </el-table-column>
      </el-table>
      <div class="pagination"><el-pagination :current-page="entryPage" :page-size="100" :total="result.parsedLines" layout="total, prev, pager, next" @current-change="loadEntries" /></div>
    </section>

    <el-dialog v-model="rawLineVisible" :title="`第 ${selectedLineNo} 行 · 脱敏原始日志`" width="min(900px, 95vw)">
      <pre class="raw-line-detail">{{ selectedRawLine }}</pre>
    </el-dialog>
  </div>
</template>

<style scoped>
.log-import { max-width: 1500px; margin: auto; }
.page-header { padding: 0 0 18px; border-bottom: 1px solid var(--csys-border-subtle); }
.kicker { display: block; color: var(--csys-faint); font-size: 11px; font-weight: 600; letter-spacing: .12em; text-transform: uppercase; }
h1 { margin: 8px 0 0; color: var(--csys-text); font-size: 26px; letter-spacing: -.02em; }
h2 { margin: 0; font-size: 14px; font-weight: 600; color: var(--csys-text); }
.panel { margin-top: 16px; padding: 18px 20px; background: var(--csys-surface); border: 1px solid var(--csys-border-subtle); border-radius: 8px; }
.panel__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.format-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.format-row label { font-size: 12px; color: var(--csys-muted); }
.hint { font-size: 12px; color: var(--csys-faint); margin: 12px 0 0; line-height: 1.6; }
.upload { margin-top: 12px; width: 100%; }
.upload :deep(.el-upload-dragger) { background: var(--csys-surface-2); border: 1px dashed var(--csys-border); border-radius: 8px; padding: 28px 16px; }
.upload :deep(.el-upload-dragger:hover) { border-color: var(--csys-accent); }
.upload :deep(.el-icon--upload) { color: var(--csys-accent); font-size: 40px; }
.upload-tip { font-size: 12px; color: var(--csys-faint); margin-top: 8px; }
.action { margin-top: 14px; }
.batch-badge { font-size: 13px; color: var(--csys-muted); }
.batch-id { color: var(--csys-accent); font-size: 15px; font-weight: 600; }
.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin: 4px 0 12px; }
.stat { padding: 12px 14px; background: var(--csys-surface-2); border: 1px solid var(--csys-border-subtle); border-radius: 6px; }
.stat span { display: block; font-size: 12px; color: var(--csys-muted); }
.stat strong { display: block; margin-top: 6px; font-size: 18px; font-weight: 600; color: var(--csys-text); font-variant-numeric: tabular-nums; }
.stat strong.ok { color: var(--csys-success); }
.stat strong.skip { color: var(--csys-medium); }
.pagination { display: flex; justify-content: flex-end; margin-top: 14px; overflow: auto; }
.raw-line-detail { max-height: 60vh; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; color: var(--csys-text); line-height: 1.6; font-size: 12px; }
@media (max-width: 768px) { .stats { grid-template-columns: 1fr 1fr; } }
</style>