<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeMenu = computed(() => route.path)
const pageTitle = computed(() => ({ '/home': '安全概览', '/logs': '日志导入与预览', '/events': '规则检测与事件', '/audit': '审计记录' }[route.path] || '工作台'))

const THEME_KEY = 'csys_theme'
const isDark = ref(true)

onMounted(() => {
  isDark.value = document.documentElement.classList.contains('dark')
})

function toggleTheme(): void {
  isDark.value = !isDark.value
  document.documentElement.classList.toggle('dark', isDark.value)
  localStorage.setItem(THEME_KEY, isDark.value ? 'dark' : 'light')
}

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
          <button class="layout__theme" type="button" :aria-label="isDark ? '切换到明亮模式' : '切换到暗黑模式'" :title="isDark ? '切换到明亮模式' : '切换到暗黑模式'" @click="toggleTheme">
            <svg v-if="isDark" viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="4.2" stroke="currentColor" stroke-width="1.7"/><path d="M12 2.5v2.2M12 19.3v2.2M4.2 12H2M22 12h-2.2M5.6 5.6l1.6 1.6M16.8 16.8l1.6 1.6M18.4 5.6l-1.6 1.6M7.2 16.8l-1.6 1.6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>
            <svg v-else viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/></svg>
          </button>
          <div class="layout__avatar" aria-hidden="true">{{ (auth.username || 'U').slice(0, 1).toUpperCase() }}</div>
          <div class="layout__identity"><strong>{{ auth.username || '未登录' }}</strong><span>{{ auth.role || '-' }}</span></div>
          <el-button class="layout__logout" plain @click="handleLogout">退出登录</el-button>
        </div>
      </header>
      <main class="layout__main">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout { min-height: 100vh; display: flex; background: var(--csys-bg); }

/* 侧栏 */
.layout__aside {
  position: sticky; top: 0; align-self: flex-start; box-sizing: border-box;
  width: 236px; height: 100vh; flex: 0 0 236px; overflow-y: auto;
  display: flex; flex-direction: column;
  background: var(--csys-header);
  border-right: 1px solid var(--csys-border-subtle);
  color: var(--csys-text);
}
.layout__brand { display: flex; align-items: center; gap: 11px; padding: 22px 18px; }
.layout__mark {
  width: 34px; height: 34px; display: grid; place-items: center; flex: none;
  border: 1px solid var(--csys-accent-border); border-radius: 8px;
  color: var(--csys-accent); background: var(--csys-accent-soft);
}
.layout__mark svg { width: 20px; height: 20px; }
.layout__brand-name { font-size: 14px; font-weight: 600; letter-spacing: -0.01em; color: var(--csys-text); }
.layout__brand-caption { margin-top: 2px; color: var(--csys-faint); font-size: 11px; }

.layout__nav-label {
  padding: 8px 20px 10px; color: var(--csys-faint);
  font-size: 10px; font-weight: 600; letter-spacing: 0.14em; text-transform: uppercase;
}
.layout__nav-label span { margin-left: 6px; font-weight: 400; letter-spacing: 0.02em; text-transform: none; }

.layout__menu { padding: 0 10px; border: 0; background: transparent; }
.layout__menu :deep(.el-menu-item) {
  height: 40px; margin-bottom: 2px; padding: 0 12px !important;
  border-radius: 6px; color: var(--csys-muted); font-size: 13px; line-height: 40px;
}
.layout__menu :deep(.el-menu-item:hover) {
  color: var(--csys-text); background: var(--csys-hover);
}
.layout__menu :deep(.el-menu-item.is-active) {
  color: var(--csys-accent); background: var(--csys-accent-soft); font-weight: 500;
  box-shadow: inset 2px 0 0 var(--csys-accent);
}
.layout__icon {
  width: 17px; height: 17px; margin-right: 11px; flex: none;
  stroke: currentColor; stroke-width: 1.6; stroke-linecap: round; stroke-linejoin: round;
}

.layout__aside-footer {
  margin: auto 18px 18px; padding-top: 14px;
  border-top: 1px solid var(--csys-border-subtle);
  color: var(--csys-faint); font-size: 11px;
}
.layout__status-dot {
  display: inline-block; width: 6px; height: 6px; margin-right: 8px; border-radius: 50%;
  background: var(--csys-success); box-shadow: 0 0 0 3px rgba(46, 160, 67, 0.15);
}

/* 顶栏 */
.layout__workspace { min-width: 0; flex: 1; }
.layout__header {
  height: 60px; display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 0 28px; border-bottom: 1px solid var(--csys-border-subtle); background: var(--csys-bg);
}
.layout__location { display: flex; align-items: center; gap: 10px; color: var(--csys-muted); font-size: 13px; white-space: nowrap; }
.layout__location strong { color: var(--csys-text); font-weight: 500; }
.layout__separator { color: var(--csys-faint); }
.layout__user { display: flex; align-items: center; gap: 10px; min-width: 0; }

/* 主题切换按钮 */
.layout__theme {
  display: grid; place-items: center; width: 32px; height: 32px; flex: none;
  border: 1px solid var(--csys-border); border-radius: 7px;
  color: var(--csys-muted); background: transparent; cursor: pointer;
  transition: color .18s ease, background-color .18s ease, border-color .18s ease, transform .25s ease;
}
.layout__theme svg { width: 17px; height: 17px; }
.layout__theme:hover {
  color: var(--csys-text); background: var(--csys-hover); border-color: var(--csys-border);
  transform: rotate(-18deg);
}

.layout__avatar {
  width: 30px; height: 30px; flex: none; display: grid; place-items: center; border-radius: 7px;
  color: var(--csys-accent); background: var(--csys-accent-soft); font-size: 13px; font-weight: 600;
}
.layout__identity { display: flex; flex-direction: column; gap: 1px; min-width: 0; max-width: 140px; line-height: 1.3; font-size: 12px; }
.layout__identity strong { overflow: hidden; color: var(--csys-text); font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.layout__identity span { overflow: hidden; color: var(--csys-faint); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.layout__logout {
  margin-left: 8px; height: 30px; padding: 0 12px; font-size: 12px;
  color: var(--csys-muted); background: transparent; border-color: var(--csys-border);
}
.layout__logout:hover { color: var(--csys-text); border-color: var(--csys-muted); background: var(--csys-hover); }
.layout__main { padding: 28px 32px; }

@media (max-width: 768px) {
  .layout { display: block; }
  .layout__aside { position: static; width: 100%; height: auto; overflow: visible; border-right: 0; }
  .layout__brand { padding: 14px 18px; }
  .layout__nav-label, .layout__aside-footer { display: none; }
  .layout__menu { display: flex; overflow-x: auto; padding: 0 12px 8px; }
  .layout__menu :deep(.el-menu-item) { flex: none; margin: 0 4px 0 0; }
  .layout__header { height: 56px; padding: 0 20px; }
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
