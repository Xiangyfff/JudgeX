export type Verdict = 'AC' | 'WA' | 'TLE' | 'MLE' | 'RE' | 'CE' | 'OLE' | 'SYSTEM_ERROR'
export type Status = 'PENDING' | 'QUEUED' | 'RUNNING' | 'DONE'

export interface User {
  id: number
  username: string
  displayName: string
  role: 'USER' | 'ADMIN'
  admin?: boolean
}

export interface ProblemSummary {
  id: number
  slug: string
  title: string
  difficulty: 'EASY' | 'MEDIUM' | 'HARD'
  tags: string
  timeLimitMs: number
  memoryLimitMb: number
  published: boolean
  submissions: number
  accepted: number
  acceptRate: number
}

export interface TestCaseView {
  id: number
  ordinal: number
  input: string
  expectedOutput: string
  points: number
  hidden: boolean
}

export interface ProblemDetail {
  summary: ProblemSummary
  statement: string
  cases: TestCaseView[]
}

export interface SubmissionRow {
  id: number
  userId: number
  user: string
  problemId: number
  problemTitle: string
  problemSlug: string
  language: string
  status: Status
  verdict: Verdict | null
  score: number
  totalTimeMs: number
  passedCases: number
  totalCases: number
  createdAt: string
  finishedAt: string | null
}

export interface TestResultRow {
  ordinal: number
  verdict: Verdict
  timeMs: number
  memoryKb: number
  stdout: string
  stderr: string
  points: number
}

export interface SubmissionDetail {
  row: SubmissionRow
  sourceCode: string
  compileOutput: string | null
  cases: TestResultRow[]
}

export interface WorkerView {
  id: string
  host: string
  concurrency: number
  activeTasks: number
  lastHeartbeat: string
  heartbeatAgeMs: number
  state: 'ONLINE' | 'STALE'
}

export interface QueuePoint {
  t: string
  queued: number
  running: number
}

export interface LanguageStatus {
  id: string
  name: string
  extension: string
  available: boolean
  runtime: string
}

export interface Dashboard {
  queued: number
  running: number
  capacity: number
  workersOnline: number
  activeWorkers: number
  totalSubmissions: number
  acceptedSubmissions: number
  acceptRate: number
  avgJudgeMs: number
  submissionsToday: number
  series: QueuePoint[]
  recent: SubmissionRow[]
  workerList: WorkerView[]
  languages: LanguageStatus[]
}

const TOKEN_KEY = 'judgex.token'
const USER_KEY = 'judgex.user'

export interface LeaderboardRow {
  rank: number
  name: string
  solved: number
  totalMs: number
}

export interface PresenceView {
  redis: string
  nodes: string[]
}

export interface MqStats {
  enabled: boolean
  broker: string
  consumer: string
  topic: string
  pending: number
  sending: number
  sent: number
  dead: number
  lastError: string | null
}

export interface NotificationRow {
  id: number
  submissionId: number
  verdict: string
  message: string
  createdAt: string
}

export const session = {
  get token(): string | null {
    return localStorage.getItem(TOKEN_KEY)
  },
  get user(): User | null {
    const raw = localStorage.getItem(USER_KEY)
    if (!raw) return null
    try {
      return JSON.parse(raw)
    } catch {
      return null
    }
  },
  save(token: string, user: User) {
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  },
  clear() {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }
}

export class ApiError extends Error {
  code: string
  status: number
  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Content-Type', 'application/json')
  const token = session.token
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const res = await fetch(`/api${path}`, { ...init, headers })
  if (res.status === 401) {
    session.clear()
    if (!location.pathname.startsWith('/login')) {
      location.assign('/login')
    }
    throw new ApiError(401, 'UNAUTHORIZED', '登录已过期，请重新登录')
  }
  if (!res.ok) {
    let code = 'ERROR'
    let message = `请求失败 (${res.status})`
    try {
      const body = await res.json()
      code = body.code ?? code
      message = body.message ?? message
    } catch {
      /* non-json error */
    }
    throw new ApiError(res.status, code, message)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export const api = {
  login: (username: string, password: string) =>
    call<{ token: string; expiresIn: number; user: User }>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    }),
  me: () => call<User>('/auth/me'),
  health: () => call<Record<string, unknown>>('/health'),
  languages: () => call<LanguageStatus[]>('/languages'),
  problems: () => call<ProblemSummary[]>('/problems'),
  problem: (id: number) => call<ProblemDetail>(`/problems/${id}`),
  submissions: (params: { mine?: boolean; problemId?: number; limit?: number }) => {
    const q = new URLSearchParams()
    if (params.mine) q.set('mine', 'true')
    if (params.problemId) q.set('problemId', String(params.problemId))
    q.set('limit', String(params.limit ?? 30))
    return call<SubmissionRow[]>(`/submissions?${q.toString()}`)
  },
  submission: (id: number) => call<SubmissionDetail>(`/submissions/${id}`),
  createSubmission: (payload: { problemId: number; language: string; sourceCode: string }) =>
    call<SubmissionRow>('/submissions', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify(payload)
    }),
  dashboard: () => call<Dashboard>('/dashboard'),
  leaderboard: () => call<LeaderboardRow[]>('/leaderboard'),
  presence: () => call<PresenceView>('/presence'),
  mqStats: () => call<MqStats>('/mq/stats'),
  notifications: (limit = 5) => call<NotificationRow[]>(`/notifications?limit=${limit}`),
  workers: () => call<WorkerView[]>('/workers'),
  saveProblem: (payload: Record<string, unknown>, id?: number) =>
    call<ProblemDetail>(id ? `/admin/problems/${id}` : '/admin/problems', {
      method: id ? 'PUT' : 'POST',
      body: JSON.stringify(payload)
    }),
  deleteProblem: (id: number) => call<void>(`/admin/problems/${id}`, { method: 'DELETE' }),
  replaceCases: (id: number, cases: unknown[]) =>
    call<ProblemDetail>(`/admin/problems/${id}/cases`, {
      method: 'PUT',
      body: JSON.stringify(cases)
    })
}

export function streamUrl(path: string): string {
  const token = session.token ?? ''
  return `/api${path}${path.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`
}
