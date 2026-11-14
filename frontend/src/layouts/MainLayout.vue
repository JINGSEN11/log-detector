<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeMenu = computed(() => route.path)
const pageTitle = computed(() => ({ '/home': '安全概览', '/logs': '日志导入与预览', '/events': '规则检测与事件', '/audit': '审计记录' }[route.path] || '工作台'))

async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  auth.logout()
  await router.push('/login')
}
</script>

<template>
  <div class="layout">
    <aside class="layout__aside">
      <div class="layout__brand">
        <div class="layout__mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none"><path d="M12 2.5 20 6v5.6c0 4.8-3.2 8.1-8 9.9-4.8-1.8-8-5.1-8-9.9V6l8-3.5Z" stroke="currentColor" stroke-width="1.7"/><path d="m8.5 12 2.3 2.3 4.8-5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </div>
        <div><div class="layout__brand-name">Log Detector</div><div class="layout__brand-caption">日志安全检测平台</div></div>
      </div>

      <div class="layout__nav-label">WORKSPACE <span>工作台</span></div>
      <el-menu :default-active="activeMenu" router class="layout__menu">
        <el-menu-item index="/home">
          <svg class="layout__icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><rect x="3.5" y="3.5" width="7" height="7" rx="1.5"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.5"/><rect x="3.5" y="13.5" width="7" height="7" rx="1.5"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.5"/></svg>
          <span>安全概览</span>
        </el-menu-item>
        <el-menu-item index="/logs">
          <svg class="layout__icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 4.5h10l4 4v11H5z"/><path d="M15 4.5v4h4M8 12h8M8 15.5h8"/></svg>
          <span>日志导入与预览</span>
        </el-menu-item>
        <el-menu-item index="/events">
          <svg class="layout__icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 3 3 20h18L12 3Z"/><path d="M12 9v5m0 3h.01"/></svg>
          <span>规则检测与事件</span>
        </el-menu-item>
        <el-menu-item v-if="auth.role === 'ADMIN'" index="/audit">
          <svg class="layout__icon" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 4.5h14v15H5zM8 9h8M8 12.5h8M8 16h5"/></svg>
          <span>审计记录</span>
        </el-menu-item>
      </el-menu>
      <div class="layout__aside-footer"><span class="layout__status-dot"></span>安全分析工作台</div>
    </aside>

    <div class="layout__workspace">
      <header class="layout__header">
        <div class="layout__location"><span>工作台</span><span class="layout__separator">/</span><strong>{{ pageTitle }}</strong></div>
        <div class="layout__user">
          <div class="layout__avatar" aria-hidden="true">{{ (auth.username || 'U').slice(0, 1).toUpperCase() }}</div>
          <div class="layout__identity"><strong>{{ auth.username || '未登录' }}</strong><span>{{ auth.role || '-' }}</span></div>
          <el-button class="layout__logout" plain @click="handleLogout">退出登录</el-button>
        </div>
      </header>
      <main class="layout__main"><router-view /></main>
    </div>
  </div>
</template>

<style scoped>
.layout { min-height: 100vh; display: flex; background: var(--csys-bg); }
.layout__aside { position: sticky; top: 0; align-self: flex-start; box-sizing: border-box; width: 240px; height: 100vh; flex: 0 0 240px; overflow-y: auto; display: flex; flex-direction: column; background: var(--csys-header); color: #fff; }
.layout__brand { display: flex; align-items: center; gap: 12px; padding: 27px 22px 31px; }
.layout__mark { width: 38px; height: 38px; display: grid; place-items: center; flex: none; border: 1px solid rgba(113, 188, 255, .28); border-radius: 11px; color: #8dcaff; background: #233e5d; }
.layout__mark svg { width: 23px; height: 23px; }
.layout__brand-name { font-size: 15px; font-weight: 700; letter-spacing: .02em; }
.layout__brand-caption { margin-top: 4px; color: #8fa3bb; font-size: 11px; letter-spacing: .05em; }
.layout__nav-label { padding: 0 23px 12px; color: #71839a; font-size: 10px; font-weight: 700; letter-spacing: .12em; }
.layout__nav-label span { margin-left: 6px; font-weight: 400; letter-spacing: 0; }
.layout__menu { padding: 0 12px; border: 0; background: transparent; }
.layout__menu :deep(.el-menu-item) { height: 46px; margin-bottom: 5px; padding: 0 12px !important; border-radius: 8px; color: #aab9cb; font-size: 13px; }
.layout__menu :deep(.el-menu-item:hover) { color: #fff; background: #1c3048; }
.layout__menu :deep(.el-menu-item.is-active) { color: #fff; background: #294866; font-weight: 600; }
.layout__icon { width: 18px; height: 18px; margin-right: 12px; flex: none; stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round; }
.layout__aside-footer { margin: auto 22px 24px; padding-top: 18px; border-top: 1px solid #2a3a4d; color: #8295ab; font-size: 12px; }
.layout__status-dot { display: inline-block; width: 7px; height: 7px; margin-right: 8px; border-radius: 50%; background: #8bb6dc; }
.layout__workspace { min-width: 0; flex: 1; }
.layout__header { height: 72px; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 0 32px; border-bottom: 1px solid var(--csys-border); background: #fff; }
.layout__location { display: flex; align-items: center; gap: 12px; color: #91a0b1; font-size: 13px; white-space: nowrap; }
.layout__location strong { color: #263a50; font-weight: 600; }
.layout__separator { color: #c8d1dc; }
.layout__user { display: flex; align-items: center; gap: 10px; min-width: 0; }
.layout__avatar { width: 36px; height: 36px; flex: none; display: grid; place-items: center; border-radius: 10px; color: #2864a4; background: #e9f2fb; font-size: 14px; font-weight: 700; }
.layout__identity { display: flex; flex-direction: column; gap: 2px; min-width: 0; max-width: 140px; line-height: 1.3; font-size: 12px; }
.layout__identity strong { overflow: hidden; color: #263a50; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.layout__identity span { overflow: hidden; color: #728398; text-overflow: ellipsis; white-space: nowrap; }
.layout__logout { margin-left: 12px; min-height: 44px; }
.layout__main { padding: 30px 32px; }
@media (max-width: 768px) {
  .layout { display: block; }
  .layout__aside { position: static; width: 100%; height: auto; overflow: visible; }
  .layout__brand { padding: 16px 20px; }
  .layout__nav-label, .layout__aside-footer { display: none; }
  .layout__menu { display: flex; overflow-x: auto; padding: 0 12px 8px; }
  .layout__menu :deep(.el-menu-item) { flex: none; margin: 0 4px 0 0; }
  .layout__header { height: 64px; padding: 0 20px; }
  .layout__main { padding: 20px; }
}
@media (max-width: 480px) {
  .layout__location > span { display: none; }
  .layout__identity { max-width: 80px; overflow: hidden; }
  .layout__identity strong, .layout__identity span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .layout__logout { margin-left: 0; }
  .layout__main { padding: 16px; }
}
</style>
