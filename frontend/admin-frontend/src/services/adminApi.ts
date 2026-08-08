import api from './api'

export const adminApi = {
  getPriceUpdateConfig: () => api.get('/api/admin/config/price-update'),
  updatePriceUpdateConfig: (data: any) => api.put('/api/admin/config/price-update', data),
  getSystemHealth: () => api.get('/api/admin/monitoring/system-health'),
  getActiveUsers: () => api.get('/api/admin/monitoring/active-users'),
  getFunds: () => api.get('/api/funds'),
  createFund: (data: any) => api.post('/api/funds', data),
  updateFund: (id: number, data: any) => api.put(`/api/funds/${id}`, data),
  deleteFund: (id: number) => api.delete(`/api/funds/${id}`),
  updateFundConfig: (id: number, data: any) => api.put(`/api/funds/${id}/config`, data),
}
