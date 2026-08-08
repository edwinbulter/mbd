import axios from 'axios'
import keycloak from '@/utils/keycloak'

const api = axios.create({
  baseURL: '/', // Same origin as frontend (Istio Gateway handles /api)
})

api.interceptors.request.use(
  (config: any) => {
    const token = keycloak.token
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error: any) => {
    return Promise.reject(error)
  }
)

export default api
