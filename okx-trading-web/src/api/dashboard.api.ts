import request from './request'

export const dashboardApi = {
  getOverview() {
    return request.get('/dashboard/overview')
  }
}
