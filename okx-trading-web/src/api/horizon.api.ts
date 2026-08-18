import request from './request'

export interface HorizonDigestView {
  noteId?: string
  title: string
  date?: string
  lang?: string
  markdown?: string
  snippet?: string
  haloPermalink?: string
  updatedAt?: string
}

export interface HorizonDigestBrief {
  noteId?: string
  title: string
  date?: string
  lang?: string
  snippet?: string
  haloPermalink?: string
  updatedAt?: string
}

export const horizonApi = {
  latest(lang = 'zh', date?: string) {
    return request.get('/v1/horizon/latest', { params: { lang, date } }) as Promise<{
      data: HorizonDigestView | null
    }>
  },
  recent(lang = 'zh', limit = 7) {
    return request.get('/v1/horizon/recent', { params: { lang, limit } }) as Promise<{
      data: HorizonDigestBrief[]
    }>
  },
  refresh() {
    return request.post('/v1/horizon/refresh', {}, { timeout: 1_200_000 }) as Promise<{
      data: HorizonRefreshStatus
    }>
  },
  refreshStatus() {
    return request.get('/v1/horizon/refresh/status') as Promise<{ data: HorizonRefreshStatus }>
  },
  publish(date?: string) {
    return request.post('/v1/horizon/publish', null, {
      params: date ? { date } : {},
      timeout: 120000
    }) as Promise<{ data: HorizonRefreshStatus }>
  }
}

export interface HorizonRefreshStatus {
  enabled: boolean
  running: boolean
  lastStartedAt?: string
  lastFinishedAt?: string
  lastOk?: boolean
  lastMessage?: string
  lastPublished?: boolean
  lastPermalink?: string
}
