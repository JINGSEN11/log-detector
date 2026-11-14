<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  username: 'admin',
  password: 'admin123'
})

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function submit(): Promise<void> {
  if (!formRef.value || loading.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/home'
    await router.push(redirect)
  } catch {
    // 失败提示由 Axios 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <section class="login__intro">
      <div class="login__brand"><span class="login__mark" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M12 2.5 20 6v5.6c0 4.8-3.2 8.1-8 9.9-4.8-1.8-8-5.1-8-9.9V6l8-3.5Z" stroke="currentColor" stroke-width="1.7"/><path d="m8.5 12 2.3 2.3 4.8-5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg></span><span>Log Detector<small>日志安全检测平台</small></span></div>
      <div class="login__intro-content">
        <div class="login__eyebrow"><span></span> SECURITY WORKSPACE</div>
        <h1>让每一条日志<br>都更有价值。</h1>
        <p>从日志导入到安全研判，在一个清晰的工作空间中探索潜在风险。</p>
        <div class="login__features"><span>日志管理</span><i></i><span>安全分析</span><i></i><span>智能研判</span></div>
      </div>
      <div class="login__intro-footer">LOG DETECTOR <span> / </span> SECURITY OBSERVABILITY</div>
    </section>

    <section class="login__panel">
      <div class="login__form-wrap">
        <div class="login__form-eyebrow">WELCOME BACK</div>
        <h2>欢迎登录</h2>
        <p class="login__subtitle">登录您的账号，进入安全检测工作台。</p>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" clearable />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" autocomplete="current-password" show-password clearable />
          </el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" class="login__submit">登录工作台 <span aria-hidden="true">→</span></el-button>
        </el-form>
      </div>
      <div class="login__panel-footer">日志攻击检测与智能研判平台</div>
    </section>
  </div>
</template>

<style scoped>
.login { display: flex; min-height: 100vh; background: var(--csys-surface); }
.login__intro { position: relative; display: flex; flex: 1 1 52%; flex-direction: column; overflow: hidden; min-height: 100vh; padding: 38px 8%; color: #fff; background: radial-gradient(ellipse at 80% 68%, #214766 0, transparent 48%), linear-gradient(145deg, #111e30, #172a40); }
.login__intro::after { position: absolute; right: -160px; bottom: -220px; width: 560px; height: 560px; border: 1px solid rgba(163, 207, 240, .12); border-radius: 50%; box-shadow: 0 0 0 65px rgba(163, 207, 240, .035), 0 0 0 130px rgba(163, 207, 240, .025); content: ''; pointer-events: none; }
.login__brand { display: flex; align-items: center; gap: 12px; font-size: 15px; font-weight: 700; letter-spacing: .02em; }
.login__brand small { display: block; margin-top: 4px; color: #95aac0; font-size: 11px; font-weight: 400; letter-spacing: .05em; }
.login__mark { display: grid; width: 40px; height: 40px; place-items: center; border: 1px solid rgba(113, 188, 255, .28); border-radius: 11px; color: #8dcaff; background: #233e5d; }
.login__mark svg { width: 23px; height: 23px; }
.login__intro-content { position: relative; z-index: 1; margin: auto 0; padding: 80px 0; }
.login__eyebrow, .login__form-eyebrow { color: #8fb9d8; font-size: 11px; font-weight: 700; letter-spacing: .16em; }
.login__eyebrow span { display: inline-block; width: 7px; height: 7px; margin-right: 8px; border-radius: 50%; background: #6dd3bd; }
.login__intro h1 { margin: 26px 0 20px; font-size: clamp(32px, 3.5vw, 48px); line-height: 1.35; letter-spacing: .02em; }
.login__intro p { max-width: 390px; color: #aabdd0; font-size: 15px; line-height: 1.9; }
.login__features { display: flex; align-items: center; gap: 14px; margin-top: 38px; color: #c1d1df; font-size: 12px; }
.login__features i { width: 3px; height: 3px; border-radius: 50%; background: #68849d; }
.login__intro-footer { position: relative; z-index: 1; color: #718aa2; font-size: 10px; letter-spacing: .12em; }
.login__intro-footer span { margin: 0 8px; }
.login__panel { position: relative; display: flex; flex: 1 1 48%; align-items: center; justify-content: center; padding: 60px 40px; }
.login__form-wrap { width: 100%; max-width: 380px; }
.login__form-eyebrow { color: var(--csys-accent); }
.login__form-wrap h2 { margin: 14px 0 8px; color: var(--csys-text); font-size: 30px; }
.login__subtitle { margin: 0 0 36px; color: var(--csys-muted); font-size: 14px; line-height: 1.6; }
.login :deep(.el-form-item) { margin-bottom: 22px; }
.login :deep(.el-form-item__label) { color: var(--csys-muted); font-weight: 600; }
.login :deep(.el-input__wrapper) { min-height: 46px; padding: 0 14px; border-radius: 8px; box-shadow: 0 0 0 1px var(--csys-border) inset; }
.login :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px var(--el-color-primary) inset; }
.login__submit { width: 100%; height: 46px; margin-top: 6px; border-radius: 8px; font-weight: 600; }
.login__submit span span { margin-left: 12px; font-size: 18px; }
.login__hint { margin-top: 28px; padding: 14px 16px; border: 1px solid var(--csys-border); border-radius: 8px; color: var(--csys-muted); background: var(--csys-surface-2); font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.login__hint strong { color: var(--csys-muted); font-weight: 600; }
.login__panel-footer { position: absolute; bottom: 28px; color: var(--csys-muted); font-size: 12px; }
@media (max-width: 768px) {
  .login { display: block; }
  .login__intro { min-height: 0; padding: 24px; }
  .login__intro-content { padding: 42px 0 30px; }
  .login__intro h1 { margin: 18px 0 12px; font-size: 30px; }
  .login__intro p { margin-bottom: 0; font-size: 13px; }
  .login__features { margin-top: 22px; }
  .login__intro-footer { display: none; }
  .login__panel { min-height: 510px; padding: 46px 24px 80px; }
}
</style>
