<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '../components/AppShell.vue'
import CodeEditor from '../components/CodeEditor.vue'
import VerdictPill from '../components/VerdictPill.vue'
import { api, streamUrl, type LanguageStatus, type ProblemDetail, type SubmissionDetail } from '../lib/api'
import { renderMarkdown } from '../lib/markdown'
import { fmtDuration, timeAgo } from '../lib/format'
import { toast } from '../lib/toast'

const route = useRoute()
const router = useRouter()

const problem = ref<ProblemDetail | null>(null)
const languages = ref<LanguageStatus[]>([])
const lang = ref('java')
const code = ref('')
const loading = ref(true)

type Phase = 'idle' | 'running' | 'done'
const phase = ref<Phase>('idle')
const submission = ref<SubmissionDetail | null>(null)
const liveCases = ref<{ ordinal: number; verdict: string; timeMs: number }[]>([])
const statusText = ref('')
const compileLog = ref('')
const submitting = ref(false)
const elapsed = ref(0)

let es: EventSource | null = null
let timer: number | undefined

const templates: Record<string, string> = {
  java: `import java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        // 在这里写你的代码\n    }\n}\n`,
  python: `import sys\n\ndef main():\n    data = sys.stdin.read().split()\n    # 在这里写你的代码\n\nif __name__ == "__main__":\n    main()\n`,
  javascript: `const data = require('fs').readFileSync(0, 'utf8').trim().split(/\\s+/)\n// 在这里写你的代码\n`,
  cpp: `#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    // 在这里写你的代码\n    return 0;\n}\n`
}

const summary = computed(() => problem.value?.summary ?? null)
const diffLabel: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' }
const diffClass: Record<string, string> = { EASY: 'diff-easy', MEDIUM: 'diff-medium', HARD: 'diff-hard' }

const totalOverride = ref(0)

const totalCases = computed(
  () => submission.value?.row.totalCases || totalOverride.value || problem.value?.cases.length || 0
)
const passedCases = computed(() => {
  if (submission.value) return submission.value.row.passedCases
  return liveCases.value.filter((c) => c.verdict === 'AC').length
})
const progress = computed(() => {
  const total = totalCases.value || 1
  if (phase.value === 'done') return 100
  return Math.min(100, Math.round((liveCases.value.length / total) * 100))
})
const verdict = computed(() => submission.value?.row.verdict ?? null)

function applyDetail(detail: SubmissionDetail) {
  submission.value = detail
  liveCases.value = detail.cases.map((c) => ({ ordinal: c.ordinal, verdict: c.verdict, timeMs: c.timeMs }))
  compileLog.value = detail.compileOutput ?? ''
  if (detail.row.status === 'DONE') {
    phase.value = 'done'
    statusText.value = ''
    if (timer) window.clearInterval(timer)
  }
}

async function load() {
  loading.value = true
  try {
    const id = Number(route.params.id)
    const [detail, langs] = await Promise.all([api.problem(id), api.languages()])
    problem.value = detail
    languages.value = langs
    const firstAvailable = langs.find((l) => l.available)?.id
    if (firstAvailable) lang.value = firstAvailable
    code.value = templates[lang.value] ?? ''
  } catch (e) {
    toast(e instanceof Error ? e.message : '加载题目失败', 'err')
  } finally {
    loading.value = false
  }
}

watch(lang, (next, prev) => {
  const prevTpl = templates[prev]
  const nextTpl = templates[next] ?? ''
  if (!code.value.trim() || code.value === prevTpl) code.value = nextTpl
})

function resetCode() {
  code.value = templates[lang.value] ?? ''
  toast('已恢复代码模板', 'info')
}

async function submit() {
  if (!problem.value || submitting.value) return
  if (!code.value.trim()) {
    toast('代码不能为空', 'err')
    return
  }
  submitting.value = true
  phase.value = 'running'
  submission.value = null
  liveCases.value = []
  compileLog.value = ''
  statusText.value = '已提交，等待调度…'
  elapsed.value = 0
  startTimer()
  try {
    const row = await api.createSubmission({
      problemId: problem.value.summary.id,
      language: lang.value,
      sourceCode: code.value
    })
    totalOverride.value = row.totalCases
    openStream(row.id)
  } catch (e) {
    phase.value = 'idle'
    if (timer) window.clearInterval(timer)
    toast(e instanceof Error ? e.message : '提交失败', 'err')
  } finally {
    submitting.value = false
  }
}

function startTimer() {
  if (timer) window.clearInterval(timer)
  const started = Date.now()
  timer = window.setInterval(() => {
    elapsed.value = Date.now() - started
  }, 100)
}

function openStream(id: number) {
  es?.close()
  es = new EventSource(streamUrl(`/submissions/${id}/events`))

  es.addEventListener('snapshot', (ev) => {
    const payload = JSON.parse((ev as MessageEvent).data)
    if (payload?.submission) applyDetail(payload.submission as SubmissionDetail)
  })

  es.addEventListener('status', (ev) => {
    const p = JSON.parse((ev as MessageEvent).data)
    const s = p?.payload?.status
    if (s === 'RUNNING') statusText.value = '沙箱启动中…'
    else if (s === 'QUEUED') statusText.value = '排队中…'
  })

  es.addEventListener('compiled', (ev) => {
    const p = JSON.parse((ev as MessageEvent).data)
    compileLog.value = p?.payload?.log ?? ''
    statusText.value = '逐用例评测中…'
  })

  es.addEventListener('case', (ev) => {
    const p = JSON.parse((ev as MessageEvent).data)
    const c = p?.payload
    if (!c) return
    if (c.total) totalOverride.value = c.total
    liveCases.value = [
      ...liveCases.value.filter((x) => x.ordinal !== c.ordinal),
      { ordinal: c.ordinal, verdict: c.verdict, timeMs: c.timeMs }
    ].sort((a, b) => a.ordinal - b.ordinal)
  })

  es.addEventListener('submission:done', (ev) => {
    const p = JSON.parse((ev as MessageEvent).data)
    const payload = p?.payload
    if (payload?.row) {
      submission.value = {
        row: payload.row,
        sourceCode: code.value,
        compileOutput: payload.compileOutput ?? '',
        cases: payload.cases ?? []
      }
      liveCases.value = (payload.cases ?? []).map((c: any) => ({
        ordinal: c.ordinal,
        verdict: c.verdict,
        timeMs: c.timeMs
      }))
      compileLog.value = payload.compileOutput ?? ''
    }
    phase.value = 'done'
    statusText.value = ''
    if (timer) window.clearInterval(timer)
    es?.close()
  })

  es.onerror = () => {
    // Streams are best-effort; the detail endpoint is the source of truth.
    if (phase.value === 'running') pollOnce(id)
  }
}

async function pollOnce(id: number) {
  try {
    const detail = await api.submission(id)
    applyDetail(detail)
  } catch {
    /* ignore */
  }
}

onMounted(load)
onBeforeUnmount(() => {
  es?.close()
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <AppShell :title="summary?.title ?? '题目'" section="JudgeX" flush>
    <div v-if="loading" class="empty"><i class="spinner" style="margin: 0 auto"></i></div>

    <div v-else-if="problem && summary" class="work">
      <section class="pane statement">
        <div class="st-head">
          <div class="back" @click="router.push('/problems')">← 返回题库</div>
          <div class="title-row">
            <h1>{{ summary.title }}</h1>
            <span class="diff" :class="diffClass[summary.difficulty]">{{ diffLabel[summary.difficulty] }}</span>
          </div>
          <div class="meta">
            <span class="mono">时间 {{ summary.timeLimitMs }}ms</span>
            <span class="mono">内存 {{ summary.memoryLimitMb }}MB</span>
            <span class="mono">{{ summary.submissions }} 次提交</span>
            <span class="tag" v-for="t in (summary.tags ?? '').split(',').filter(Boolean)" :key="t">{{ t }}</span>
          </div>
        </div>

        <div class="st-body">
          <div class="md" v-html="renderMarkdown(problem.statement)"></div>

          <div class="sample">
            <div class="sample-head">
              <span class="eyebrow">samples</span>
              <span class="mono dim">{{ problem.cases.length }} 个可见样例</span>
            </div>
            <div v-for="c in problem.cases.slice(0, 3)" :key="c.id" class="sample-row">
              <div class="sample-col">
                <span>输入</span>
                <pre>{{ c.hidden ? '（隐藏）' : c.input }}</pre>
              </div>
              <div class="sample-col">
                <span>期望输出</span>
                <pre>{{ c.hidden ? '（隐藏）' : c.expectedOutput }}</pre>
              </div>
            </div>
            <div v-if="problem.cases.length > 3" class="dim more mono">
              还有 {{ problem.cases.length - 3 }} 个隐藏样例
            </div>
          </div>
        </div>
      </section>

      <section class="pane bench">
        <div class="bench-bar">
          <select v-model="lang" class="input lang">
            <option v-for="l in languages" :key="l.id" :value="l.id" :disabled="!l.available">
              {{ l.name }}{{ l.available ? '' : ' (不可用)' }}
            </option>
          </select>
          <span class="spacer"></span>
          <button class="btn btn-sm btn-ghost" @click="resetCode">重置模板</button>
          <button class="btn btn-sm btn-primary" :disabled="submitting || phase === 'running'" @click="submit">
            <i v-if="submitting" class="spinner" style="width: 12px; height: 12px; border-width: 2px"></i>
            {{ phase === 'running' ? '判题中…' : '提交评测' }}
          </button>
        </div>

        <div class="editor-wrap">
          <CodeEditor v-model="code" :language="lang" />
        </div>

        <div class="result" :class="phase">
          <div class="res-head">
            <div class="card-title">
              <span class="dot" :class="phase"></span>
              判题结果
            </div>
            <div class="res-meta">
              <span v-if="phase === 'running'" class="mono dim">{{ fmtDuration(elapsed) }}</span>
              <span v-if="phase === 'done' && submission" class="mono dim">
                {{ fmtDuration(submission.row.totalTimeMs) }} · 得分 {{ submission.row.score }}
              </span>
            </div>
          </div>

          <div class="res-body">
            <div v-if="phase === 'idle'" class="idle">
              <div class="idle-icon">▷</div>
              <div>
                <b>还没有提交</b>
                <p>点击「提交评测」，进度会通过 SSE 实时推送到这里。</p>
              </div>
            </div>

            <div v-else class="live">
              <div class="live-top">
                <VerdictPill
                  v-if="phase === 'done' && verdict"
                  :verdict="verdict"
                  size="md"
                />
                <span v-else class="pill pill-live">
                  <i class="dot-spin"></i>
                  {{ statusText || '排队中…' }}
                </span>
                <span v-if="phase === 'running'" class="mono dim cases-count">
                  {{ liveCases.length }}/{{ totalCases || '?' }} 用例
                </span>
              </div>

              <div class="progress" v-if="phase === 'running'">
                <i :style="{ width: progress + '%' }"></i>
              </div>

              <div class="case-strip">
                <div
                  v-for="c in liveCases"
                  :key="c.ordinal"
                  class="case-chip"
                  :class="c.verdict === 'AC' ? 'ok' : 'bad'"
                >
                  <span class="no">#{{ c.ordinal }}</span>
                  <span class="vd">{{ c.verdict }}</span>
                  <span class="tm mono">{{ c.timeMs }}ms</span>
                </div>
                <div v-if="phase === 'running' && !liveCases.length" class="dim waiting mono">
                  等待第一个用例结果…
                </div>
              </div>

              <pre v-if="compileLog && phase !== 'running'" class="compile-log">{{ compileLog }}</pre>

              <div v-if="phase === 'done' && submission" class="done-link">
                <button class="btn btn-sm btn-ghost" @click="router.push({ path: '/submissions', query: { open: submission.row.id } })">
                  查看完整提交记录 →
                </button>
                <span class="dim mono">{{ timeAgo(submission.row.finishedAt) }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  </AppShell>
</template>

<style scoped>
.work {
  display: grid;
  grid-template-columns: minmax(360px, 44%) 1fr;
  height: 100%;
  min-height: 0;
}

.pane {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.statement {
  border-right: 1px solid var(--border);
  background: rgba(7, 11, 19, 0.5);
}

.st-head {
  padding: 20px 26px 16px;
  border-bottom: 1px solid var(--border);
  flex: none;
}
.back {
  font-size: 12px;
  color: var(--mute);
  cursor: pointer;
  margin-bottom: 12px;
  width: fit-content;
  transition: color 0.15s;
}
.back:hover {
  color: var(--cyan);
}
.title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}
.title-row h1 {
  font-size: 21px;
  margin: 0;
  letter-spacing: -0.01em;
}
.meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
  flex-wrap: wrap;
  font-size: 11px;
  color: var(--mute);
}

.st-body {
  overflow-y: auto;
  padding: 20px 26px 40px;
  flex: 1;
}

.sample {
  margin-top: 26px;
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 14px 16px;
  background: rgba(12, 18, 30, 0.6);
}
.sample-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.sample-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 10px;
}
.sample-col span {
  font-size: 10.5px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--mute);
}
.sample-col pre {
  margin: 4px 0 0;
  padding: 9px 11px;
  border-radius: 8px;
  background: rgba(4, 8, 14, 0.8);
  border: 1px solid var(--border);
  font-family: var(--mono);
  font-size: 12px;
  color: #cbd5e1;
  overflow-x: auto;
  white-space: pre-wrap;
}
.more {
  font-size: 11px;
  margin-top: 4px;
}

.bench {
  background: rgba(5, 8, 15, 0.6);
}

.bench-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
  flex: none;
  background: rgba(9, 14, 24, 0.7);
}
.lang {
  width: 150px;
  height: 32px;
  font-family: var(--mono);
  font-size: 12px;
}
.spacer {
  flex: 1;
}

.editor-wrap {
  flex: 1;
  min-height: 0;
  position: relative;
}

.result {
  flex: none;
  border-top: 1px solid var(--border);
  background: rgba(8, 12, 20, 0.9);
  transition: height 0.2s;
  max-height: 46%;
  display: flex;
  flex-direction: column;
}
.result.idle {
  height: 118px;
}
.result.running,
.result.done {
  height: 230px;
}
.res-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 11px 16px;
  border-bottom: 1px solid var(--border);
  flex: none;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--mute);
}
.dot.running {
  background: var(--cyan);
  box-shadow: 0 0 8px var(--cyan);
}
.dot.done {
  background: var(--green);
  box-shadow: 0 0 8px var(--green);
}
.res-body {
  padding: 14px 16px;
  overflow-y: auto;
  flex: 1;
}

.idle {
  display: flex;
  gap: 14px;
  align-items: center;
  color: var(--mute);
}
.idle-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  border: 1px dashed var(--border-hi);
  color: var(--cyan);
  font-size: 15px;
  flex: none;
}
.idle b {
  color: var(--dim);
  font-size: 13px;
}
.idle p {
  margin: 3px 0 0;
  font-size: 12px;
}

.live-top {
  display: flex;
  align-items: center;
  gap: 12px;
}
.cases-count {
  font-size: 11px;
}
.progress {
  height: 3px;
  background: rgba(148, 163, 184, 0.14);
  border-radius: 99px;
  margin: 12px 0;
  overflow: hidden;
}
.progress i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #22d3ee, #818cf8);
  transition: width 0.3s ease;
}

.case-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 12px;
}
.case-chip {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 5px 9px;
  border-radius: 8px;
  font-family: var(--mono);
  font-size: 10.5px;
  border: 1px solid var(--border);
  background: rgba(148, 163, 184, 0.05);
  animation: rise 0.3s ease both;
}
.case-chip .no {
  color: var(--mute);
}
.case-chip.ok {
  border-color: rgba(52, 211, 153, 0.32);
  background: rgba(52, 211, 153, 0.08);
  color: #8ff0c8;
}
.case-chip.bad {
  border-color: rgba(248, 113, 113, 0.32);
  background: rgba(248, 113, 113, 0.08);
  color: #ffb4b4;
}
.case-chip .tm {
  color: #5b6b84;
}
.waiting {
  font-size: 11px;
}

.compile-log {
  margin: 12px 0 0;
  padding: 9px 11px;
  border-radius: 8px;
  background: rgba(4, 7, 12, 0.9);
  border: 1px solid rgba(248, 113, 113, 0.25);
  color: #fca5a5;
  font-family: var(--mono);
  font-size: 11px;
  max-height: 84px;
  overflow: auto;
  white-space: pre-wrap;
}

.done-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  font-size: 11px;
}

.dot-spin {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: blink 1s infinite;
}
@keyframes blink {
  0%,
  100% {
    opacity: 0.25;
  }
  50% {
    opacity: 1;
  }
}
@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(5px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

@media (max-width: 980px) {
  .work {
    grid-template-columns: 1fr;
    grid-template-rows: 40% 60%;
  }
  .statement {
    border-right: none;
    border-bottom: 1px solid var(--border);
  }
}
</style>
