import api from './api'

export const customerApi = {
  getProfile: () => api.get('/api/users/profile'),
  register: (data: any) => api.post('/api/users/register', data),
  getAccounts: (userId: number) => api.get(`/api/accounts/user/${userId}`),
  createAccount: (data: any) => api.post('/api/accounts', data),
  getFunds: () => api.get('/api/funds'),
  deposit: (accountId: number, amount: number) => 
    api.post(`/api/accounts/${accountId}/deposit`, { amount }),
  getPortfolio: (accountId: number) => api.get(`/api/portfolio/${accountId}`),
}
