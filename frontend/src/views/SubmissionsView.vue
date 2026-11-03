<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '../components/AppShell.vue'
import VerdictPill from '../components/VerdictPill.vue'
import { api, type SubmissionDetail, type SubmissionRow } from '../lib/api'
import { fmtBytes, fmtDuration, fmtClock, timeAgo } from '../lib/format'
import { toast } from '../lib/toast'

const route = useRoute()
const rows = ref<SubmissionRow[]>([])
const loading = ref(true)
const mine = ref(true)
const detail = ref<SubmissionDetail | null>(null)
const detailLoading = ref(false)
const open = ref(false)

let poll: number | undefined

const hasActive = computed(() => rows.value.some((r) => r.status !== 'DONE'))

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    rows.value = await api.submissions({ mine: mine.value, limit: 50 })
  } catch (e) {
    if (!silent) toast(e instanceof Error ? e.message : '加载失败', 'err')
  } finally {
    loading.value = false
  }
}

async function openDetail(id: number) {
  detailLoading.value = true
  open.value = true
  try {
    detail.value = await api.submission(id)
  } catch (e) {
    toast(e instanceof Error ? e.message : '加载详情失败', 'err')
    open.value = false
  } finally {
    detailLoading.value = false
  }
}

watch(mine, () => load())

onMounted(async () => {
  await load()
  const target = Number(route.query.open)
  if (target) openDetail(target)
  poll = window.setInterval(() => {
    if (hasActive.value) load(true)
  }, 3000)
})

onBeforeUnmount(() => {
  if (poll) window.clearInterval(poll)
})
</script>

<template>
  <AppShell title="提交记录" section="JudgeX">
    <div class="head rise">
      <div>
        <div class="eyebrow">submissions</div>
        <h1>提交记录</h1>
        <p>每次提交都是一条状态机轨迹：排队、调度、执行、回传。</p>
      </div>
      <div class="actions">
        <div class="seg">
          <button :class="{ on: mine }" @click="mine = true">我的</button>
          <button :class="{ on: !mine }" @click="mine = false">全部</button>
        </div>
        <button class="btn btn-sm" @click="load()">刷新</button>
      </div>
    </div>

    <div class="card table-card">
      <div v-if="loading" class="empty"><i class="spinner" style="margin: 0 auto"></i></div>
      <div v-else-if="!rows.length" class="empty">还没有提交记录，去题库挑一道题吧</div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th style="width: 74px">#</th>
            <th style="width: 110px">结果</th>
            <th>题目</th>
            <th style="width: 96px">语言</th>
            <th style="width: 84px">用例</th>
            <th style="width: 92px">耗时</th>
            <th style="width: 110px">提交者</th>
            <th style="width: 110px">时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.id" @click="openDetail(r.id)">
            <td class="mono muted">{{ r.id }}</td>
            <td><VerdictPill :verdict="r.verdict" :status="r.status" /></td>
            <td class="strong">
              {{ r.problemTitle }}
              <span v-if="r.status !== 'DONE'" class="live-tag">判题中</span>
            </td>
            <td class="mono">{{ r.language }}</td>
            <td class="mono">{{ r.passedCases }}/{{ r.totalCases }}</td>
            <td class="mono">{{ fmtDuration(r.totalTimeMs) }}</td>
            <td>{{ r.user }}</td>
            <td class="dim" :title="fmtClock(r.createdAt)">{{ timeAgo(r.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <transition name="drawer">
      <div v-if="open" class="overlay" @click.self="open = false">
        <aside class="drawer">
          <div class="dr-head">
            <div>
              <div class="eyebrow">submission detail</div>
              <h2 v-if="detail">
                #{{ detail.row.id }} · {{ detail.row.problemTitle }}
              </h2>
              <h2 v-else>加载中…</h2>
            </div>
            <button class="btn btn-sm btn-ghost" @click="open = false">关闭</button>
          </div>

          <div v-if="detailLoading" class="empty"><i class="spinner" style="margin: 0 auto"></i></div>

          <div v-else-if="detail" class="dr-body">
            <div class="summary">
              <VerdictPill :verdict="detail.row.verdict" :status="detail.row.status" />
              <span class="mono dim">{{ detail.row.passedCases }}/{{ detail.row.totalCases }} 用例</span>
              <span class="mono dim">{{ fmtDuration(detail.row.totalTimeMs) }}</span>
              <span class="mono dim">score {{ detail.row.score }}</span>
              <span class="mono dim">{{ detail.row.language }}</span>
            </div>

            <div class="cases">
              <div v-for="c in detail.cases" :key="c.ordinal" class="case" :class="c.verdict === 'AC' ? 'ok' : 'bad'">
                <div class="case-top">
                  <span class="mono">用例 #{{ c.ordinal }}</span>
                  <b>{{ c.verdict }}</b>
                </div>
                <div class="case-meta mono">
                  {{ c.timeMs }}ms · {{ fmtBytes(c.memoryKb) }} · {{ c.points }} 分
                </div>
                <pre v-if="c.stderr" class="case-err">{{ c.stderr }}</pre>
                <pre v-else-if="c.stdout" class="case-out">{{ c.stdout.slice(0, 400) }}</pre>
              </div>
            </div>

            <pre v-if="detail.compileOutput" class="compile-log">{{ detail.compileOutput }}</pre>

            <div class="src-head">
              <span class="eyebrow">source code</span>
              <span class="mono dim">{{ detail.sourceCode.length }} 字符</span>
            </div>
            <pre class="src">{{ detail.sourceCode }}</pre>
          </div>
        </aside>
      </div>
    </transition>
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
.actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.seg {
  display: flex;
  padding: 3px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: rgba(10, 16, 28, 0.6);
}
.seg button {
  border: none;
  background: transparent;
  color: var(--dim);
  font-family: inherit;
  font-size: 12.5px;
  padding: 5px 14px;
  border-radius: 7px;
  cursor: pointer;
  transition: 0.15s;
}
.seg button.on {
  background: rgba(34, 211, 238, 0.14);
  color: #a5f3fc;
  box-shadow: inset 0 0 0 1px rgba(34, 211, 238, 0.3);
}

.table-card {
  overflow: hidden;
}
.muted {
  color: #4b5b74;
}
.dim {
  color: var(--mute);
}
.live-tag {
  font-size: 10px;
  color: var(--cyan);
  border: 1px solid rgba(34, 211, 238, 0.3);
  border-radius: 5px;
  padding: 0 5px;
  margin-left: 8px;
  animation: blink 1.4s infinite;
}
@keyframes blink {
  50% {
    opacity: 0.45;
  }
}

.overlay {
  position: fixed;
  inset: 0;
  background: rgba(3, 6, 12, 0.62);
  backdrop-filter: blur(3px);
  z-index: 80;
  display: flex;
  justify-content: flex-end;
}
.drawer {
  width: min(640px, 92vw);
  height: 100%;
  background: #080d16;
  border-left: 1px solid var(--border-hi);
  display: flex;
  flex-direction: column;
  box-shadow: -30px 0 80px rgba(0, 0, 0, 0.6);
}
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity 0.22s;
}
.drawer-enter-active .drawer,
.drawer-leave-active .drawer {
  transition: transform 0.26s cubic-bezier(0.16, 1, 0.3, 1);
}
.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}
.drawer-enter-from .drawer,
.drawer-leave-to .drawer {
  transform: translateX(40px);
}

.dr-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 18px 22px;
  border-bottom: 1px solid var(--border);
}
.dr-head h2 {
  font-size: 17px;
  margin: 4px 0 0;
}
.dr-body {
  padding: 18px 22px 40px;
  overflow-y: auto;
}
.summary {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border);
  font-size: 11.5px;
}
.cases {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 9px;
  margin: 16px 0;
}
.case {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 10px 12px;
  background: rgba(148, 163, 184, 0.04);
}
.case.ok {
  border-color: rgba(52, 211, 153, 0.24);
}
.case.bad {
  border-color: rgba(248, 113, 113, 0.24);
}
.case-top {
  display: flex;
  justify-content: space-between;
  font-size: 11.5px;
}
.case.ok .case-top b {
  color: #6ee7b7;
}
.case.bad .case-top b {
  color: #fca5a5;
}
.case-meta {
  font-size: 10.5px;
  color: var(--mute);
  margin-top: 3px;
}
.case-err,
.case-out {
  margin: 8px 0 0;
  font-family: var(--mono);
  font-size: 10.5px;
  max-height: 90px;
  overflow: auto;
  white-space: pre-wrap;
  color: #94a3b8;
  background: rgba(4, 7, 12, 0.7);
  padding: 6px 8px;
  border-radius: 6px;
}
.case-err {
  color: #fca5a5;
}

.compile-log {
  margin: 0 0 16px;
  padding: 11px 13px;
  border-radius: 9px;
  background: rgba(4, 7, 12, 0.85);
  border: 1px solid rgba(248, 113, 113, 0.25);
  color: #fca5a5;
  font-family: var(--mono);
  font-size: 11.5px;
  white-space: pre-wrap;
  max-height: 180px;
  overflow: auto;
}
.src-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.src {
  margin: 0;
  padding: 14px 16px;
  border-radius: 10px;
  background: #04070d;
  border: 1px solid var(--border);
  font-family: var(--mono);
  font-size: 12px;
  line-height: 1.7;
  color: #c6d2e6;
  overflow: auto;
  white-space: pre;
}
</style>
