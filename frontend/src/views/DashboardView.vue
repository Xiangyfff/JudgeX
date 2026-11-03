<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AppShell from '../components/AppShell.vue'
import VerdictPill from '../components/VerdictPill.vue'
import { api, type Dashboard, type LeaderboardRow, type MqStats, type NotificationRow, type PresenceView } from '../lib/api'
import { fmtDuration, timeAgo } from '../lib/format'
import { toast } from '../lib/toast'

const dash = ref<Dashboard | null>(null)
const leaderboard = ref<LeaderboardRow[]>([])
const presenceView = ref<PresenceView>({ redis: 'DOWN', nodes: [] })
const mq = ref<MqStats | null>(null)
const notifications = ref<NotificationRow[]>([])
const loading = ref(true)
const lastUpdated = ref<Date | null>(null)
let poll: number | undefined

const W = 620
const H = 130
const PAD = 8

const chart = computed(() => {
  const pts = dash.value?.series ?? []
  if (pts.length < 2) return { queued: '', running: '', area: '', max: 1 }
  const max = Math.max(1, ...pts.map((p) => Math.max(p.queued, p.running)))
  const x = (i: number) => PAD + (i * (W - PAD * 2)) / (pts.length - 1)
  const y = (v: number) => H - PAD - (v / max) * (H - PAD * 2)
  const line = (key: 'queued' | 'running') =>
    pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(p[key]).toFixed(1)}`).join(' ')
  const q = line('queued')
  return {
    queued: q,
    running: line('running'),
    area: `${q} L${x(pts.length - 1).toFixed(1)},${H - PAD} L${x(0).toFixed(1)},${H - PAD} Z`,
    max
  }
})

const kpis = computed(() => {
  const d = dash.value
  if (!d) return []
  return [
    { label: '排队中', value: d.queued, hint: `并发容量 ${d.capacity}`, tone: 'cyan' },
    {
      label: '执行中',
      value: d.running,
      hint: `${d.workersOnline} 节点在线 · 并发 ${d.capacity}`,
      tone: 'violet'
    },
    { label: '累计提交', value: d.totalSubmissions, hint: `今日 ${d.submissionsToday}`, tone: 'plain' },
    { label: '通过率', value: `${(d.acceptRate <= 1 ? d.acceptRate * 100 : d.acceptRate).toFixed(1)}%`, hint: `${d.acceptedSubmissions} 次通过`, tone: 'green' },
    { label: '平均判题', value: fmtDuration(d.avgJudgeMs), hint: '端到端 P50', tone: 'amber' }
  ]
})

function brokerClass(state?: string) {
  if (state === 'UP') return 'pill-ac'
  if (state === 'DEGRADED') return 'pill-tle'
  return 'pill-sys'
}

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const [d, board, presence, stats, feed] = await Promise.all([
      api.dashboard(),
      api.leaderboard().catch(() => [] as LeaderboardRow[]),
      api.presence().catch(() => ({ redis: 'DOWN', nodes: [] as string[] })),
      api.mqStats().catch(() => null),
      api.notifications(6).catch(() => [] as NotificationRow[])
    ])
    dash.value = d
    leaderboard.value = board
    presenceView.value = presence
    mq.value = stats
    notifications.value = feed
    lastUpdated.value = new Date()
  } catch (e) {
    if (!silent) toast(e instanceof Error ? e.message : '加载监控数据失败', 'err')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  poll = window.setInterval(() => load(true), 3000)
})
onBeforeUnmount(() => {
  if (poll) window.clearInterval(poll)
})
</script>

<template>
  <AppShell title="运行监控" section="JudgeX">
    <div class="head rise">
      <div>
        <div class="eyebrow">operations</div>
        <h1>运行监控</h1>
        <p>队列深度、节点心跳、判题吞吐 —— 调度器的仪表盘。</p>
      </div>
      <div class="live">
        <i class="pulse"></i>
        <span class="mono">LIVE · {{ lastUpdated ? lastUpdated.toLocaleTimeString('zh-CN') : '—' }}</span>
      </div>
    </div>

    <div v-if="loading" class="empty"><i class="spinner" style="margin: 0 auto"></i></div>

    <template v-else-if="dash">
      <div class="kpis">
        <div v-for="k in kpis" :key="k.label" class="stat" :class="`tone-${k.tone}`">
          <div class="stat-label">{{ k.label }}</div>
          <div class="stat-value">{{ k.value }}</div>
          <div class="stat-hint">{{ k.hint }}</div>
        </div>
      </div>

      <div class="row2">
        <div class="card card-pad chart-card">
          <div class="card-head">
            <div class="card-title">队列深度 · 最近 2 分钟</div>
            <div class="legend">
              <span><i class="lg-cyan"></i>排队</span>
              <span><i class="lg-violet"></i>执行</span>
            </div>
          </div>
          <svg :viewBox="`0 0 ${W} ${H}`" class="chart" preserveAspectRatio="none">
            <defs>
              <linearGradient id="qArea" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="#22d3ee" stop-opacity="0.28" />
                <stop offset="100%" stop-color="#22d3ee" stop-opacity="0" />
              </linearGradient>
            </defs>
            <line v-for="i in 4" :key="i" :x1="PAD" :x2="W - PAD" :y1="(H / 4) * i" :y2="(H / 4) * i" stroke="rgba(148,163,184,0.1)" stroke-width="1" />
            <path :d="chart.area" fill="url(#qArea)" />
            <path :d="chart.queued" fill="none" stroke="#22d3ee" stroke-width="1.8" stroke-linejoin="round" />
            <path :d="chart.running" fill="none" stroke="#8b5cf6" stroke-width="1.8" stroke-linejoin="round" stroke-dasharray="4 3" />
          </svg>
          <div class="chart-foot mono">
            <span>peak {{ chart.max }}</span>
            <span>{{ dash.series.length }} 个采样点 · 每 5s</span>
          </div>
        </div>

        <div class="card card-pad">
          <div class="card-title">执行节点</div>
          <div class="workers">
            <div v-for="w in dash.workerList" :key="w.id" class="worker">
              <div class="w-top">
                <span class="mono w-id">{{ w.id }}</span>
                <span class="pill" :class="w.state === 'ONLINE' ? 'pill-ac' : 'pill-sys'">{{ w.state }}</span>
              </div>
              <div class="w-meta mono">{{ w.host }}</div>
              <div class="w-bar">
                <i :style="{ width: Math.min(100, (w.activeTasks / Math.max(1, w.concurrency)) * 100) + '%' }"></i>
              </div>
              <div class="w-foot mono">
                <span>{{ w.activeTasks }}/{{ w.concurrency }} 任务</span>
                <span class="dim">心跳 {{ timeAgo(w.lastHeartbeat) }}</span>
              </div>
            </div>
            <div v-if="!dash.workerList.length" class="empty" style="padding: 20px">没有在线节点</div>
          </div>
        </div>
      </div>

      <div class="row2 second">
        <div class="card card-pad">
          <div class="card-title">最近提交</div>
          <table class="tbl mini">
            <thead>
              <tr>
                <th>结果</th>
                <th>题目</th>
                <th>语言</th>
                <th>耗时</th>
                <th>提交者</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in dash.recent.slice(0, 7)" :key="r.id">
                <td><VerdictPill :verdict="r.verdict" :status="r.status" size="sm" /></td>
                <td class="strong">{{ r.problemTitle }}</td>
                <td class="mono">{{ r.language }}</td>
                <td class="mono">{{ fmtDuration(r.totalTimeMs) }}</td>
                <td>{{ r.user }}</td>
                <td class="dim">{{ timeAgo(r.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-if="!dash.recent.length" class="empty" style="padding: 24px">还没有提交</div>
        </div>

        <div class="card card-pad">
          <div class="card-title">运行时</div>
          <div class="langs">
            <div v-for="l in dash.languages" :key="l.id" class="lang">
              <div>
                <b>{{ l.name }}</b>
                <span class="mono dim">{{ l.runtime }} · {{ l.extension.replace(/^\./, '') }}</span>
              </div>
              <span class="pill" :class="l.available ? 'pill-ac' : 'pill-sys'">
                {{ l.available ? 'READY' : 'MISSING' }}
              </span>
            </div>
          </div>
          <hr class="hr" />
          <div class="sys">
            <div><span>判题模式</span><b class="mono">local sandbox</b></div>
            <div><span>任务分发</span><b class="mono">pull + lease</b></div>
            <div><span>进度通道</span><b class="mono">SSE</b></div>
          </div>
        </div>
      </div>

      <div class="row2 second" style="margin-top: 14px">
        <div class="card card-pad">
          <div class="card-head">
            <div class="card-title">排行榜 · 通过题数 / 总耗时</div>
            <span class="mono dim" style="font-size: 11px">ZSET · jx:lb</span>
          </div>
          <table v-if="leaderboard.length" class="tbl mini">
            <thead>
              <tr>
                <th style="width: 46px">#</th>
                <th>选手</th>
                <th>通过</th>
                <th>总耗时</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in leaderboard" :key="r.rank">
                <td class="mono" :style="{ color: r.rank === 1 ? '#fcd34d' : undefined }">{{ r.rank }}</td>
                <td class="strong">{{ r.name }}</td>
                <td class="mono">{{ r.solved }}</td>
                <td class="mono dim">{{ fmtDuration(r.totalMs) }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty" style="padding: 22px">还没有人通过题目</div>
        </div>

        <div class="card card-pad">
          <div class="card-title">Redis</div>
          <div class="sys">
            <div>
              <span>状态</span>
              <b
                ><span class="pill" :class="presenceView.redis === 'UP' ? 'pill-ac' : 'pill-sys'">
                  {{ presenceView.redis }}
                </span></b
              >
            </div>
            <div><span>在线节点</span><b class="mono">{{ presenceView.nodes.join(', ') || '—' }}</b></div>
            <div><span>限流桶</span><b class="mono">jx:rl:submit:*</b></div>
            <div><span>存活键</span><b class="mono">jx:worker:*</b></div>
          </div>
          <p class="dim" style="font-size: 11.5px; line-height: 1.65; margin: 12px 0 0">
            只放可重建的状态：令牌桶、存活键、排名。任务与判题结果始终以数据库为准——Redis 挂了只降级，不丢数据。
          </p>
        </div>
      </div>

      <div class="row2" style="margin-top: 14px; grid-template-columns: 1.05fr 1fr">
        <div class="card card-pad">
          <div class="card-head">
            <div class="card-title">消息通道 · 事务性发件箱</div>
            <span class="mono dim" style="font-size: 11px">outbox → {{ mq?.topic ?? 'judgex-result' }}</span>
          </div>
          <div class="sys">
            <div>
              <span>Broker</span>
              <b
                ><span class="pill" :class="brokerClass(mq?.broker)">
                  {{ mq?.broker ?? '—' }}
                </span></b
              >
            </div>
            <div>
              <span>消费者</span>
              <b
                ><span class="pill" :class="mq?.consumer === 'UP' ? 'pill-ac' : 'pill-sys'">
                  {{ mq?.consumer ?? '—' }}
                </span></b
              >
            </div>
            <div><span>待投递</span><b class="mono">{{ mq?.pending ?? 0 }}</b></div>
            <div><span>已投递</span><b class="mono">{{ mq?.sent ?? 0 }}</b></div>
            <div>
              <span>死信</span>
              <b class="mono" :style="{ color: (mq?.dead ?? 0) > 0 ? 'var(--red)' : undefined }">{{ mq?.dead ?? 0 }}</b>
            </div>
          </div>
          <p class="dim" style="font-size: 11.5px; line-height: 1.65; margin: 12px 0 0">
            判题结果与发件箱行同事务提交，relay 再投递到 RocketMQ。broker 挂了新事件就留在表里按指数退避重试——不丢、不阻塞判题，恢复后自动追平。
          </p>
        </div>

        <div class="card card-pad">
          <div class="card-head">
            <div class="card-title">最近通知 · 消费端产出</div>
            <span class="mono dim" style="font-size: 11px">group judgex-notify</span>
          </div>
          <ul v-if="notifications.length" class="feed">
            <li v-for="n in notifications" :key="n.id">
              <span class="pill pill-sm" :class="n.verdict === 'AC' ? 'pill-ac' : 'pill-wa'">{{ n.verdict }}</span>
              <span class="feed-text">{{ n.message }}</span>
            </li>
          </ul>
          <div v-else class="empty" style="padding: 18px">还没有被消费的事件</div>
        </div>
      </div>
    </template>
  </AppShell>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 20px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}
h1 {
  font-size: 26px;
  margin: 4px 0 6px;
  letter-spacing: -0.02em;
}
.head p {
  color: var(--mute);
  margin: 0;
  font-size: 13px;
}
.live {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 11px;
  color: #9fefd0;
  border: 1px solid rgba(52, 211, 153, 0.25);
  background: rgba(52, 211, 153, 0.06);
  padding: 6px 12px;
  border-radius: 99px;
}

.kpis {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}
.tone-cyan .stat-value {
  color: #67e8f9;
}
.tone-violet .stat-value {
  color: #c4b5fd;
}
.tone-green .stat-value {
  color: #6ee7b7;
}
.tone-amber .stat-value {
  color: #fcd34d;
}

.row2 {
  display: grid;
  grid-template-columns: 1.55fr 1fr;
  gap: 14px;
  margin-bottom: 14px;
}
.second {
  grid-template-columns: 1.55fr 1fr;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.legend {
  display: flex;
  gap: 14px;
  font-size: 10.5px;
  color: var(--mute);
}
.legend span {
  display: flex;
  align-items: center;
  gap: 6px;
}
.legend i {
  width: 14px;
  height: 2px;
  border-radius: 2px;
}
.lg-cyan {
  background: #22d3ee;
}
.lg-violet {
  background: #8b5cf6;
}
.chart {
  width: 100%;
  height: 130px;
  display: block;
}
.chart-foot {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: #4b5b74;
  margin-top: 6px;
}

.workers {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}
.worker {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 11px 13px;
  background: rgba(148, 163, 184, 0.03);
}
.w-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.w-id {
  font-size: 12px;
  color: var(--text);
}
.w-meta {
  font-size: 10.5px;
  color: #4b5b74;
  margin-top: 2px;
}
.w-bar {
  height: 3px;
  border-radius: 99px;
  background: rgba(148, 163, 184, 0.12);
  margin: 9px 0 6px;
  overflow: hidden;
}
.w-bar i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #22d3ee, #8b5cf6);
}
.w-foot {
  display: flex;
  justify-content: space-between;
  font-size: 10.5px;
  color: var(--dim);
}
.dim {
  color: #4b5b74;
}

.mini th {
  padding: 8px 10px;
}
.mini td {
  padding: 9px 10px;
  font-size: 12px;
}

.langs {
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin-top: 12px;
}
.lang {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 9px 11px;
  border-radius: 9px;
  border: 1px solid var(--border);
  background: rgba(148, 163, 184, 0.03);
}
.lang b {
  display: block;
  font-size: 12.5px;
}
.lang span {
  font-size: 10.5px;
}
.sys {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
}
.sys div {
  display: flex;
  justify-content: space-between;
  color: var(--mute);
}
.sys b {
  color: var(--dim);
  font-weight: 500;
  font-size: 11px;
}

@media (max-width: 1100px) {
  .kpis {
    grid-template-columns: repeat(2, 1fr);
  }
  .row2,
  .second {
    grid-template-columns: 1fr;
  }
}
</style>
