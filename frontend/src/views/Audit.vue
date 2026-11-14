<script setup lang="ts">
import { onMounted } from 'vue'
import { useAudit } from './useAudit'

const { rows, page, size, total, loading, error, loadPage } = useAudit()
onMounted(() => { void loadPage(1) })
</script>

<template>
  <div class="audit">
    <header class="page-header">
      <span class="kicker">ADMIN / AUDIT</span>
      <h1>审计记录</h1>
      <p>接口响应仍可能包含详情并传输至浏览器；记录新增时，翻页结果可能重复或遗漏。</p>
    </header>
    <section class="panel">
      <div v-if="loading" class="state" role="status">正在加载审计记录…</div>
      <div v-else-if="error" class="state" role="alert">
        <p>审计记录加载失败，请重试。</p>
        <el-button @click="loadPage(page)">重试</el-button>
      </div>
      <div v-else-if="rows.length === 0" class="state">暂无审计记录</div>
      <el-table v-else :data="rows">
        <el-table-column label="ID" width="72"><template #default="s"><code class="mono">{{ s.row.id }}</code></template></el-table-column>
        <el-table-column label="时间" min-width="175"><template #default="s"><code class="mono">{{ s.row.createdAt }}</code></template></el-table-column>
        <el-table-column prop="actorUserId" label="操作人" width="100" />
        <el-table-column prop="action" label="操作" min-width="160" />
        <el-table-column prop="targetType" label="目标类型" width="130" />
        <el-table-column label="目标 ID" width="110"><template #default="s"><code class="mono">{{ s.row.targetId || '—' }}</code></template></el-table-column>
        <el-table-column label="追踪 ID" min-width="200" show-overflow-tooltip><template #default="s"><code class="mono">{{ s.row.traceId || '—' }}</code></template></el-table-column>
      </el-table>
      <el-pagination v-if="!error" class="pager" layout="total, prev, pager, next"
        :total="total" :current-page="page" :page-size="size" :disabled="loading"
        @current-change="loadPage" />
    </section>
  </div>
</template>

<style scoped>
.audit { max-width: 1500px; margin: auto; }
.page-header { padding: 0 0 18px; border-bottom: 1px solid var(--csys-border-subtle); }
.kicker { display: block; color: var(--csys-faint); font-size: 11px; font-weight: 600; letter-spacing: .12em; text-transform: uppercase; }
h1 { margin: 8px 0 0; color: var(--csys-text); font-size: 26px; letter-spacing: -.02em; }
.panel { margin-top: 16px; padding: 18px 20px; background: var(--csys-surface); border: 1px solid var(--csys-border-subtle); border-radius: 8px; }
.state { padding: 48px 16px; text-align: center; color: var(--csys-faint); }
.state p { margin: 0 0 12px; }
.pager { margin-top: 14px; justify-content: flex-end; }
</style>
