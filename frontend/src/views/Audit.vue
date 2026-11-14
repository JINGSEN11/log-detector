<script setup lang="ts">
import { onMounted } from 'vue'
import { useAudit } from './useAudit'

const { rows, page, size, total, loading, error, loadPage } = useAudit()
onMounted(() => { void loadPage(1) })
</script>

<template>
  <section class="audit">
    <header class="audit__header">
      <h1>审计记录</h1>
    </header>
    <el-card shadow="never">
      <div v-if="loading" class="audit__state" role="status">正在加载审计记录…</div>
      <div v-else-if="error" class="audit__state" role="alert">
        <p>审计记录加载失败，请重试。</p>
        <el-button @click="loadPage(page)">重试</el-button>
      </div>
      <div v-else-if="rows.length === 0" class="audit__state">暂无审计记录</div>
      <el-table v-else :data="rows" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="createdAt" label="时间" min-width="170" />
        <el-table-column prop="actorUserId" label="操作用户 ID" min-width="120" />
        <el-table-column prop="action" label="操作" min-width="160" />
        <el-table-column prop="targetType" label="目标类型" min-width="130" />
        <el-table-column prop="targetId" label="目标 ID" min-width="120" />
        <el-table-column prop="traceId" label="追踪 ID" min-width="180" show-overflow-tooltip />
      </el-table>
      <el-pagination v-if="!error" class="audit__pager" layout="total, prev, pager, next"
        :total="total" :current-page="page" :page-size="size" :disabled="loading"
        @current-change="loadPage" />
    </el-card>
  </section>
</template>

<style scoped>
.audit__header { margin-bottom: 24px; }
.audit__header h1 { margin: 0 0 8px; color: #263a50; font-size: 24px; }
.audit__state { padding: 48px 16px; text-align: center; color: #728398; }
.audit__pager { margin-top: 16px; justify-content: flex-end; }
</style>