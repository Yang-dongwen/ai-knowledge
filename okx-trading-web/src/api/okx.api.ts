import request from './request'

export const okxApi = {
  getConfig() {
    return request.get('/okx/config')
  },
  saveConfig(data: any) {
    return request.post('/okx/config', data)
  },
  testConnection() {
    return request.post('/okx/test-connection')
  },
  getBalance() {
    return request.get('/okx/balance')
  }
}
