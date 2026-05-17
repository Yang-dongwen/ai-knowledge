import request from './request'

export const systemApi = {
  getStatus() {
    return request.get('/system/status')
  },
  stop() {
    return request.post('/system/stop')
  },
  resume() {
    return request.post('/system/resume')
  }
}
