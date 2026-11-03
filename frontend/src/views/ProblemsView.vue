<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '../components/AppShell.vue'
import { api, type ProblemSummary } from '../lib/api'
import { toast } from '../lib/toast'

const router = useRouter()
const problems = ref<ProblemSummary[]>([])
const loading = ref(true)
const query = ref('')
const diff = ref<'ALL' | 'EASY' | 'MEDIUM' | 'HARD'>('ALL')
const sort = ref<'default' | 'rate' | 'popular'>('default')

const diffLabel: Record<string, string> = { EASY: '简单', MEDIUM: '中等', HARD: '困难' }
const diffClass: Record<string, string> = { EASY: 'diff-easy', MEDIUM: 'diff-medium', HARD: 'diff-hard' }

const filtered = computed(() => {
  let list = problems.value.filter((p) => {
    const q = query.value.trim().toLowerCase()
    const hitQ =
      !q ||
      p.title.toLowerCase().includes(q) ||
      p.slug.toLowerCase().includes(q) ||
      (p.tags ?? '').toLowerCase().includes(q)
    const hitD = diff.value === 'ALL' || p.difficulty === diff.value
    return hitQ && hitD
  })
  if (sort.value === 'rate') {
    list = [...list].sort((a, b) => b.acceptRate - a.acceptRate)
  } else if (sort.value === 'popular') {
    list = [...list].sort((a, b) => b.submissions - a.submissions)
  }
  return list
})

const stats = computed(() => {
  const total = problems.value.length
  const subs = problems.value.reduce((s, p) => s + p.submissions, 0)
  const acc = problems.value.reduce((s, p) => s + p.accepted, 0)
  return {
    total,
    subs,
    rate: subs ? Math.round((acc / subs) * 1000) / 10 : 0,
    hard: problems.value.filter((p) => p.difficulty === 'HARD').length
  }
})

function pct(rate: number) {
  const v = rate <= 1 ? rate * 100 : rate
  return Math.round(v * 10) / 10
}

async function load() {
  loading.value = true
  try {
    problems.value = await api.problems()
  } catch (e) {
    toast(e instanceof Error ? e.message : '加载题库失败', 'err')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <AppShell title="题库" section="JudgeX">
    <div class="head rise">
      <div>
        <div class="eyebrow">problem set</div>
        <h1>题库</h1>
        <p>选一道题，写代码，看它在你眼前被逐用例判完。</p>
      </div>
      <div class="mini-stats">
        <div class="ms">
          <span>题目</span><b>{{ stats.total }}</b>
        </div>
        <div class="ms">
          <span>提交</span><b>{{ stats.subs }}</b>
        </div>
        <div class="ms">
          <span>通过率</span><b>{{ stats.rate }}%</b>
        </div>
        <div class="ms">
          <span>困难题</span><b>{{ stats.hard }}</b>
        </div>
      </div>
    </div>

    <div class="toolbar">
      <div class="search">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <circle cx="11" cy="11" r="7" />
          <path d="M20 20l-3.5-3.5" />
        </svg>
        <input v-model="query" class="input" placeholder="搜索标题、slug 或标签…" />
      </div>
      <div class="chips">
        <button
          v-for="d in ['ALL', 'EASY', 'MEDIUM', 'HARD'] as const"
          :key="d"
          class="chip"
          :class="{ on: diff === d }"
          @click="diff = d"
        >
          {{ d === 'ALL' ? '全部' : diffLabel[d] }}
        </button>
      </div>
      <select v-model="sort" class="input sorter">
        <option value="default">默认排序</option>
        <option value="rate">通过率优先</option>
        <option value="popular">热度优先</option>
      </select>
    </div>

    <div v-if="loading" class="empty"><i class="spinner" style="margin: 0 auto"></i></div>

    <div v-else-if="!filtered.length" class="empty">没有匹配的题目</div>

    <div v-else class="grid">
      <article
        v-for="(p, i) in filtered"
        :key="p.id"
        class="pcard"
        :style="{ animationDelay: `${Math.min(i * 35, 400)}ms` }"
        @click="router.push(`/problems/${p.id}`)"
      >
        <div class="pcard-top">
          <span class="idx">{{ String(p.id).padStart(2, '0') }}</span>
          <span class="diff" :class="diffClass[p.difficulty]">{{ diffLabel[p.difficulty] }}</span>
        </div>
        <h3>{{ p.title }}</h3>
        <div class="slug mono">/{{ p.slug }}</div>
        <div class="tags">
          <span v-for="t in (p.tags ?? '').split(',').filter(Boolean)" :key="t" class="tag">{{ t }}</span>
        </div>
        <div class="limits mono">
          {{ p.timeLimitMs }}ms · {{ p.memoryLimitMb }}MB
        </div>
        <div class="foot">
          <div class="bar">
            <i :style="{ width: `${Math.max(pct(p.acceptRate), 2)}%` }"></i>
          </div>
          <div class="foot-txt">
            <span>{{ pct(p.acceptRate) }}% 通过</span>
            <span class="dim">{{ p.submissions }} 次提交</span>
          </div>
        </div>
      </article>
    </div>
  </AppShell>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 24px;
  margin-bottom: 22px;
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
.mini-stats {
  display: flex;
  gap: 10px;
}
.ms {
  min-width: 78px;
  padding: 9px 13px;
  border-radius: 11px;
  border: 1px solid var(--border);
  background: rgba(14, 21, 35, 0.6);
}
.ms span {
  display: block;
  font-size: 10px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--mute);
}
.ms b {
  font-family: var(--mono);
  font-size: 17px;
  font-weight: 600;
}

.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 20px;
  flex-wrap: wrap;
}
.search {
  position: relative;
  flex: 1;
  min-width: 220px;
}
.search svg {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  width: 15px;
  height: 15px;
  color: var(--mute);
}
.search .input {
  padding-left: 35px;
  height: 38px;
}
.chips {
  display: flex;
  gap: 6px;
  padding: 4px;
  border-radius: 11px;
  border: 1px solid var(--border);
  background: rgba(10, 16, 28, 0.6);
}
.chip {
  border: none;
  background: transparent;
  color: var(--dim);
  font-size: 12.5px;
  padding: 5px 13px;
  border-radius: 8px;
  cursor: pointer;
  font-family: inherit;
  transition: 0.15s;
}
.chip:hover {
  color: var(--text);
}
.chip.on {
  background: rgba(34, 211, 238, 0.14);
  color: #a5f3fc;
  box-shadow: inset 0 0 0 1px rgba(34, 211, 238, 0.3);
}
.sorter {
  width: 140px;
  height: 38px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(292px, 1fr));
  gap: 14px;
}

.pcard {
  position: relative;
  padding: 18px 19px 15px;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  background: linear-gradient(165deg, rgba(19, 28, 45, 0.8), rgba(9, 14, 24, 0.75));
  cursor: pointer;
  transition: 0.2s ease;
  overflow: hidden;
  animation: rise 0.45s cubic-bezier(0.16, 1, 0.3, 1) both;
}
.pcard::before {
  content: "";
  position: absolute;
  inset: 0;
  background: radial-gradient(300px 120px at 100% 0%, rgba(34, 211, 238, 0.1), transparent 70%);
  opacity: 0;
  transition: opacity 0.25s;
}
.pcard:hover {
  transform: translateY(-3px);
  border-color: rgba(34, 211, 238, 0.36);
  box-shadow: 0 20px 44px -26px rgba(34, 211, 238, 0.6);
}
.pcard:hover::before {
  opacity: 1;
}

.pcard-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.idx {
  font-family: var(--mono);
  font-size: 11px;
  color: #475569;
  letter-spacing: 0.1em;
}
.pcard h3 {
  font-size: 16.5px;
  margin: 0 0 3px;
  font-weight: 600;
  letter-spacing: -0.01em;
}
.slug {
  font-size: 11px;
  color: #4b5b74;
  margin-bottom: 12px;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  min-height: 22px;
  margin-bottom: 10px;
}
.limits {
  font-size: 10.5px;
  color: #4b5b74;
  letter-spacing: 0.04em;
}
.foot {
  margin-top: 13px;
  padding-top: 12px;
  border-top: 1px solid rgba(148, 163, 184, 0.08);
}
.bar {
  height: 3px;
  border-radius: 99px;
  background: rgba(148, 163, 184, 0.12);
  overflow: hidden;
}
.bar i {
  display: block;
  height: 100%;
  border-radius: 99px;
  background: linear-gradient(90deg, #22d3ee, #34d399);
}
.foot-txt {
  display: flex;
  justify-content: space-between;
  margin-top: 7px;
  font-size: 10.5px;
  color: #6b7d99;
}
.dim {
  color: #4b5b74;
}
</style>
