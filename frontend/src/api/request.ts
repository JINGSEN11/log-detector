import axios, { type AxiosInstance, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

/** localStorage 中保存 token 的键名 */
export const TOKEN_KEY = 'csys_token'
/** localStorage 中保存用户名的键名 */
export const USERNAME_KEY = 'csys_username'
/** localStorage 中保存角色的键名 */
export const ROLE_KEY = 'csys_role'

export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

const instance: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 请求拦截：统一注入 token
instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截：拆包 data、统一错误提示、401 跳登录
instance.interceptors.response.use(
  (response) => {
    if (response.config.responseType === 'blob') return response.data
    const body = response.data as ApiResult
    if (body && typeof body.code === 'number' && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body ? body.data : response.data
  },
  async (error) => {
    const status: number | undefined = error?.response?.status
    let body = error?.response?.data
    if (body instanceof Blob && body.type.includes('json')) {
      try { body = JSON.parse(await body.text()) } catch { body = null }
    }
    const message: string = body?.message ?? error?.message ?? '网络异常，请稍后重试'
    const isLogin = error?.config?.url?.split('?')[0]?.replace(/\/$/, '') === '/auth/login'
    if (status === 401 && isLogin) {
      ElMessage.error(message)
    } else if (status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USERNAME_KEY)
      localStorage.removeItem(ROLE_KEY)
      ElMessage.warning('登录已过期，请重新登录')
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
    } else {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

/**
 * 业务代码统一用 http.xxx，返回值已经是后端 Result.data。
 */
export const http = {
  get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
    const config: AxiosRequestConfig = { params }
    return instance.get(url, config) as unknown as Promise<T>
  },
  download(url: string, params?: Record<string, unknown>): Promise<Blob> {
    return instance.get(url, { params, responseType: 'blob' }) as unknown as Promise<Blob>
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config) as unknown as Promise<T>
  },
  put<T>(url: string, data?: unknown): Promise<T> {
    return instance.put(url, data) as unknown as Promise<T>
  },
  delete<T>(url: string): Promise<T> {
    return instance.delete(url) as unknown as Promise<T>
  }
}

export default instance
