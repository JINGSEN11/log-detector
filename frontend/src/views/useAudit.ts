import { getCurrentInstance, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { auditApi, type AuditEntry, type PageResult } from '../api'
import { ROLE_KEY, TOKEN_KEY, USERNAME_KEY } from '../api/request'
import { useAuthStore } from '../stores/auth'

export function useAudit(fetchPage: (page: number, size: number) => Promise<PageResult<AuditEntry>> = auditApi.list) {
  const rows = ref<AuditEntry[]>([])
  const page = ref(1)
  const size = 20
  const total = ref(0)
  const loading = ref(false)
  const error = ref(false)
  let version = 0
  let invalidated = false

  if (getCurrentInstance()) {
    const router = useRouter()
    const auth = useAuthStore()
    const handleStorage = (event: StorageEvent) => {
      if (event.storageArea !== localStorage || (event.key !== null && ![TOKEN_KEY, USERNAME_KEY, ROLE_KEY].includes(event.key))) return
      if (event.key !== null && event.oldValue === event.newValue) return
      invalidated = true
      version++
      rows.value = []
      total.value = 0
      loading.value = false
      error.value = false
      auth.token = localStorage.getItem(TOKEN_KEY) ?? ''
      auth.username = localStorage.getItem(USERNAME_KEY) ?? ''
      auth.role = localStorage.getItem(ROLE_KEY) ?? ''
      void router.replace(auth.token ? '/home' : '/login')
    }
    onMounted(() => window.addEventListener('storage', handleStorage))
    onUnmounted(() => {
      invalidated = true
      version++
      window.removeEventListener('storage', handleStorage)
    })
  }

  async function loadPage(requestedPage: number): Promise<void> {
    if (invalidated) return
    const current = ++version
    page.value = requestedPage
    rows.value = []
    loading.value = true
    error.value = false
    try {
      const result = await fetchPage(requestedPage, size)
      if (current === version) {
        rows.value = result.list
        total.value = result.total
        page.value = result.page
      }
    } catch {
      if (current === version) error.value = true
    } finally {
      if (current === version) loading.value = false
    }
  }

  return { rows, page, size, total, loading, error, loadPage }
}
