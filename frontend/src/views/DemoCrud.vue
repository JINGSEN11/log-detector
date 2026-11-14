<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { demoApi, type DemoItem } from '../api'

const rows = ref<DemoItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<DemoItem>({ name: '', description: '', status: 'ACTIVE' })

const rules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }]
}

const dialogTitle = computed(() => (editingId.value ? '编辑记录' : '新增记录'))

async function load(targetPage: number = page.value, targetSize: number = size.value): Promise<void> {
  loading.value = true
  try {
    const data = await demoApi.page(targetPage, targetSize, keyword.value)
    rows.value = data.list
    total.value = data.total
    page.value = data.page
    size.value = data.size
  } catch {
    // 错误提示由 Axios 拦截器统一处理
  } finally {
    loading.value = false
  }
}

function handlePageChange(current: number): void {
  void load(current, size.value)
}

function handleSizeChange(pageSize: number): void {
  void load(1, pageSize)
}

function handleSearch(): void {
  void load(1, size.value)
}

function openCreate(): void {
  editingId.value = null
  form.name = ''
  form.description = ''
  form.status = 'ACTIVE'
  dialogVisible.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

function openEdit(row: DemoItem): void {
  editingId.value = row.id ?? null
  form.name = row.name
  form.description = row.description ?? ''
  form.status = row.status ?? 'ACTIVE'
  dialogVisible.value = true
  void nextTick(() => formRef.value?.clearValidate())
}

async function save(): Promise<void> {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  saving.value = true
  try {
    const payload: DemoItem = { name: form.name, description: form.description, status: form.status }
    if (editingId.value) {
      await demoApi.update(editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      await demoApi.create(payload)
      ElMessage.success('已新增')
    }
    dialogVisible.value = false
    await load()
  } catch {
    // 错误提示由 Axios 拦截器统一处理
  } finally {
    saving.value = false
  }
}

async function remove(row: DemoItem): Promise<void> {
  if (!row.id) {
    return
  }
  try {
    await ElMessageBox.confirm(`确定删除「${row.name}」吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await demoApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    // 错误提示由 Axios 拦截器统一处理
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="toolbar">
        <span>示例 CRUD：照抄本页与后端 demo 包，就是你的第一个业务模块</span>
        <div class="toolbar__actions">
          <el-input
            v-model="keyword"
            placeholder="按名称搜索"
            clearable
            class="toolbar__search"
            @keyup.enter="handleSearch"
          />
          <el-button @click="handleSearch">查询</el-button>
          <el-button type="primary" @click="openCreate">新增</el-button>
        </div>
      </div>
    </template>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column prop="description" label="描述" min-width="240" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="200" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, sizes, prev, pager, next"
      :total="total"
      :current-page="page"
      :page-size="size"
      :page-sizes="[10, 20, 50]"
      @current-change="handlePageChange"
      @size-change="handleSizeChange"
    />

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="255" show-word-limit />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-select v-model="form.status" class="dialog__select">
            <el-option label="ACTIVE" value="ACTIVE" />
            <el-option label="INACTIVE" value="INACTIVE" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.toolbar__actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.toolbar__search {
  width: 220px;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}

.dialog__select {
  width: 100%;
}
</style>
