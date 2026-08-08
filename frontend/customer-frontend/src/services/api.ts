import axios from 'axios'
import keycloak from '@/utils/keycloak'

const api = axios.create({
  baseURL: '/', // Same origin as frontend (Istio Gateway handles /api)
})

api.interceptors.request.use(
  (config) => {
    if (keycloak.token) {
      config.headers.Authorization = `Bearer ${keycloak.token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

export default api
