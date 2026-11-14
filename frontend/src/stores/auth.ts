import { defineStore } from 'pinia'
import { authApi, type LoginResponse } from '../api'
import { ROLE_KEY, TOKEN_KEY, USERNAME_KEY } from '../api/request'

interface AuthState {
  token: string
  username: string
  role: string
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(TOKEN_KEY) ?? '',
    username: localStorage.getItem(USERNAME_KEY) ?? '',
    role: localStorage.getItem(ROLE_KEY) ?? ''
  }),

  getters: {
    isLoggedIn: (state): boolean => state.token.length > 0
  },

  actions: {
    async login(username: string, password: string): Promise<void> {
      const data: LoginResponse = await authApi.login(username, password)
      this.token = data.token
      this.username = data.username
      this.role = data.role
      localStorage.setItem(TOKEN_KEY, data.token)
      localStorage.setItem(USERNAME_KEY, data.username)
      localStorage.setItem(ROLE_KEY, data.role)
    },

    logout(): void {
      this.token = ''
      this.username = ''
      this.role = ''
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USERNAME_KEY)
      localStorage.removeItem(ROLE_KEY)
    }
  }
})
