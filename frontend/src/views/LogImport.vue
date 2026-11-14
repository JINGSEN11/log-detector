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
    <header class="page-heading">
      <div><div class="eyebrow">WORKSPACE / LOG IMPORT</div><h1>日志导入</h1><p>上传日志，开始安全分析。</p></div>
    </header>

    <div class="section-title"><div><span class="section-kicker">LOG SOURCES</span><h2>选择日志来源</h2></div></div>
    <div class="source-grid">
      <section class="panel source-panel">
        <div class="panel-heading"><div><span class="section-kicker">UPLOAD</span><h2>上传日志</h2><p>拖拽或选择本地 UTF-8 日志文件</p></div><span class="panel-icon">↑</span></div>
        <div class="format-select">
          <label for="log-format">日志格式</label>
          <el-select id="log-format" v-model="selectedFormat" aria-label="日志格式" :disabled="loading">
            <el-option label="自动（按扩展名）" value="auto" />
            <el-option label="自定义 TSV" value="custom" />
          </el-select>
          <p v-if="selectedFormat === 'auto'" class="upload-tip">按扩展名解析：.log / .txt 为访问日志，.json / .jsonl 为逐行 JSON。</p>
        </div>
        <el-upload ref="uploadRef" class="upload" drag :auto-upload="false" :show-file-list="true" accept=".log,.txt,.json,.jsonl"
          :limit="1" :on-change="chooseFile" :on-remove="() => { selectedFile = null; result = null }">
          <el-icon class="el-icon--upload"><upload-filled /></el-icon>
          <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择</em></div>
          <template #tip><div class="upload-tip">支持 .log / .txt / .json / .jsonl，单文件不超过 10 MB、30000 行。</div></template>
        </el-upload>
        <el-button class="action" type="primary" :loading="loading" :disabled="!selectedFile" @click="importSelectedFile">解析并导入</el-button>
      </section>
    </div>


    <section v-if="result" class="panel result-panel">
      <div class="panel-heading"><div><span class="section-kicker">IMPORT RESULT</span><h2>导入结果</h2><p>批次 #{{ result.batchId }} · {{ result.status }}</p></div></div>
      <el-descriptions class="result-summary" :column="3" border>
        <el-descriptions-item label="批次 ID">{{ result.batchId }}</el-descriptions-item>
        <el-descriptions-item label="批次状态">{{ result.status }}</el-descriptions-item>
        <el-descriptions-item label="总行数">{{ result.totalLines }}</el-descriptions-item>
        <el-descriptions-item label="解析成功">{{ result.parsedLines }}</el-descriptions-item>
        <el-descriptions-item label="跳过行数">{{ result.skippedLines }}</el-descriptions-item>
      </el-descriptions>
      <p class="table-hint">表格可左右滚动；点击“查看原始行”查看完整脱敏内容。</p>
      <el-table v-loading="entriesLoading" :data="entries" class="table" max-height="420" scrollbar-always-on>
        <el-table-column prop="lineNo" label="行号" width="75" />
        <el-table-column prop="maskedIp" label="来源 IP（掩码）" width="150" />
        <el-table-column prop="method" label="方法" width="90" />
        <el-table-column prop="path" label="路径（脱敏）" min-width="200" show-overflow-tooltip />
        <el-table-column prop="status" label="状态码" width="90" />
        <el-table-column prop="userAgent" label="User-Agent（脱敏）" min-width="180" show-overflow-tooltip />
        <el-table-column prop="rawLine" label="原始行（脱敏）" min-width="900" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="scope"><el-button link type="primary" @click="showRawLine(scope.row)">查看原始行</el-button></template>
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
.format-select{margin-top:20px}.format-select label{display:block;margin-bottom:8px;font-size:13px;color:#526078}.format-select .el-select{width:100%}
.table-hint{margin:18px 0 0;color:#8995a8;font-size:12px}.raw-line-detail{max-height:60vh;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;color:#34445b;line-height:1.6}
.log-import{max-width:1500px;margin:auto}.page-heading{margin-bottom:24px}.eyebrow,.section-kicker{font-size:10px;font-weight:700;letter-spacing:1.3px;color:#8492a8}.page-heading h1{font-size:26px;letter-spacing:-.5px;margin:7px 0;color:#18243a}.page-heading p,.panel-heading p{margin:0;color:#8995a8;font-size:13px}.section-title{margin:30px 0 15px}.section-title h2,.panel-heading h2{font-size:16px;margin:6px 0 0;color:#202c42}.source-grid{display:grid;grid-template-columns:minmax(0,1fr);gap:16px}.panel{background:white;border:1px solid #e8edf4;border-radius:12px;box-shadow:0 3px 16px #243c6310;padding:22px;min-width:0}.panel-heading{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.panel-heading p{margin-top:8px}.panel-icon{width:34px;height:34px;display:grid;place-items:center;border-radius:10px;font-size:20px;color:#4368df;background:#edf2ff}.upload,.scenario-select{margin-top:20px;width:100%}.upload-tip,.muted{font-size:12px;line-height:1.6;color:#8995a8}.upload-tip{margin-top:9px}.action{margin-top:16px}.preview-meta{display:flex;justify-content:space-between;gap:12px;margin:12px 0;color:#8995a8;font-size:12px}.log-preview{box-sizing:border-box;min-height:120px;max-height:230px;overflow:auto;padding:14px;white-space:pre-wrap;overflow-wrap:anywhere;background:#f7f9fc;border:1px solid #eff3f8;border-radius:8px;color:#526078;font-size:12px;line-height:1.6}.replay-panel,.result-panel{margin-top:20px}.replay-controls{display:flex;flex-wrap:wrap;gap:12px;margin-top:20px}.result-summary,.table{margin-top:20px}.pagination{display:flex;justify-content:flex-end;margin-top:16px;overflow:auto}@media(max-width:1050px){.source-grid{grid-template-columns:1fr}}@media(max-width:600px){.page-heading h1{font-size:23px}.panel{padding:17px}}
</style>
