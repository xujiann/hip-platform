import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

export interface R<T = unknown> {
  code: number
  message: string
  data: T
}

const client = axios.create({ baseURL: '/api', timeout: 15000 })

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('bureau_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

client.interceptors.response.use(
  (resp) => {
    const r = resp.data as R
    if (r.code !== 0) {
      ElMessage.error(r.message || '操作失败')
      return Promise.reject(new Error(r.message))
    }
    return resp
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('bureau_token')
      router.push('/login')
      ElMessage.warning('登录已过期，请重新登录')
    } else {
      ElMessage.error(error.response?.data?.message || '网络请求失败')
    }
    return Promise.reject(error)
  },
)

export default client
