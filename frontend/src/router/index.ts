import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ROLE_KEY, TOKEN_KEY } from '../api/request'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    redirect: '/home',
    children: [
      {
        path: 'home',
        name: 'home',
        component: () => import('../views/Home.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'logs',
        name: 'logs',
        component: () => import('../views/LogImport.vue'),
        meta: { title: '日志导入与预览' }
      },
      {
        path: 'events',
        name: 'events',
        component: () => import('../views/Events.vue'),
        meta: { title: '规则检测与事件' }
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('../views/Audit.vue'),
        meta: { title: '审计记录', adminOnly: true }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/home'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 登录守卫：未登录访问受保护页面时跳登录，并记住原地址
router.beforeEach((to) => {
  const loggedIn = Boolean(localStorage.getItem(TOKEN_KEY))
  if (!to.meta.public && !loggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.adminOnly && localStorage.getItem(ROLE_KEY) !== 'ADMIN') {
    return { path: '/home' }
  }
  if (to.path === '/login' && loggedIn) {
    return { path: '/home' }
  }
  return true
})

export default router
