<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../stores/auth'
import { toast } from '../lib/toast'

const auth = useAuth()
const router = useRouter()
const route = useRoute()

const username = ref('student')
const password = ref('student123')
const error = ref('')

const demos = [
  { label: '学生视角', user: 'student', pass: 'student123', hint: '提交代码 · 查看记录' },
  { label: '管理员', user: 'admin', pass: 'admin123', hint: '题库与用例管理' }
]

function fill(user: string, pass: string) {
  username.value = user
  password.value = pass
  error.value = ''
}

async function submit() {
  error.value = ''
  if (!username.value || !password.value) {
    error.value = '请输入账号与密码'
    return
  }
  try {
    const user = await auth.login(username.value, password.value)
    toast(`欢迎回来，${user.displayName}`, 'ok')
    const redirect = route.query.redirect as string | undefined
    router.push(redirect || '/problems')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
  }
}
</script>

<template>
  <div class="login">
    <div class="glow a"></div>
    <div class="glow b"></div>

    <section class="left">
      <div class="brand">
        <div class="brand-mark">JX</div>
        <div>
          <div class="brand-name">JUDGEX</div>
          <div class="brand-sub">distributed judge platform</div>
        </div>
      </div>

      <h1>
        把不可信的代码<br />
        关进<span class="grad">可验证的沙箱</span>
      </h1>
      <p class="lede">
        提交 → 队列 → 调度 → 隔离执行 → 结果回传。一条链路里，每个环节都要回答同一个问题：
        <b>失败了怎么办？</b>
      </p>

      <div class="feats">
        <div class="feat">
          <div class="fi">01</div>
          <div>
            <b>进程级隔离</b>
            <span>超时强杀 · 内存上限 · 输出截断 · 无网络</span>
          </div>
        </div>
        <div class="feat">
          <div class="fi">02</div>
          <div>
            <b>任务不丢不重</b>
            <span>先落库再入队 · 幂等键 · 心跳租约 · 超时回收</span>
          </div>
        </div>
        <div class="feat">
          <div class="fi">03</div>
          <div>
            <b>实时可观测</b>
            <span>SSE 逐用例进度 · 队列深度 · 判题耗时分布</span>
          </div>
        </div>
      </div>

      <div class="terminal">
        <div class="term-head">
          <i></i><i></i><i></i><span>judge-worker · node-1</span>
        </div>
        <div class="term-body">
          <div><span class="t-dim">$</span> claim task #4217 <span class="t-cyan">java</span></div>
          <div><span class="t-dim">›</span> sandbox up in <span class="t-amber">182ms</span></div>
          <div><span class="t-dim">›</span> case 1/5 <span class="t-green">AC</span> 12ms</div>
          <div><span class="t-dim">›</span> case 2/5 <span class="t-green">AC</span> 9ms</div>
          <div><span class="t-dim">›</span> verdict <span class="t-green">ACCEPTED</span> · score 100</div>
        </div>
      </div>
    </section>

    <section class="right">
      <div class="card login-card rise">
        <div class="eyebrow">sign in</div>
        <h2>登录工作台</h2>
        <p class="sub">使用演示账号即可体验完整判题链路</p>

        <form @submit.prevent="submit">
          <label class="field">
            <span>账号</span>
            <input v-model="username" class="input" placeholder="用户名" autocomplete="username" />
          </label>
          <label class="field">
            <span>密码</span>
            <input
              v-model="password"
              class="input"
              type="password"
              placeholder="密码"
              autocomplete="current-password"
            />
          </label>
          <div v-if="error" class="err">{{ error }}</div>
          <button class="btn btn-primary submit" :disabled="auth.loading" type="submit">
            <i v-if="auth.loading" class="spinner"></i>
            {{ auth.loading ? '正在验证…' : '进入平台' }}
          </button>
        </form>

        <div class="divider"><span>演示账号</span></div>

        <div class="demos">
          <button v-for="d in demos" :key="d.user" class="demo" @click="fill(d.user, d.pass)">
            <b>{{ d.label }}</b>
            <code>{{ d.user }} / {{ d.pass }}</code>
            <span>{{ d.hint }}</span>
          </button>
        </div>
      </div>
      <div class="foot">JudgeX · Spring Boot 3 · Vue 3 · H2 · SSE</div>
    </section>
  </div>
</template>

<style scoped>
.login {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  align-items: center;
  gap: 40px;
  padding: 48px 6vw;
  overflow: hidden;
}
.glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
}
.glow.a {
  width: 520px;
  height: 520px;
  background: rgba(34, 211, 238, 0.16);
  top: -160px;
  left: -120px;
}
.glow.b {
  width: 460px;
  height: 460px;
  background: rgba(139, 92, 246, 0.16);
  bottom: -180px;
  right: 8%;
}

.left {
  position: relative;
  max-width: 620px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 38px;
}

h1 {
  font-size: 42px;
  line-height: 1.22;
  letter-spacing: -0.02em;
  margin: 0 0 18px;
  font-weight: 700;
}
.grad {
  background: linear-gradient(100deg, #22d3ee, #a78bfa 70%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.lede {
  color: var(--dim);
  font-size: 14.5px;
  line-height: 1.8;
  max-width: 520px;
  margin: 0 0 30px;
}
.lede b {
  color: #dbeafe;
}

.feats {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 30px;
}
.feat {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  padding: 11px 14px;
  border-radius: 12px;
  border: 1px solid var(--border);
  background: rgba(12, 19, 32, 0.55);
  max-width: 520px;
}
.fi {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--cyan);
  padding-top: 2px;
  letter-spacing: 0.1em;
}
.feat b {
  display: block;
  font-size: 13.5px;
  margin-bottom: 2px;
}
.feat span {
  font-size: 12px;
  color: var(--mute);
}

.terminal {
  border-radius: 12px;
  border: 1px solid var(--border);
  background: rgba(4, 7, 13, 0.85);
  overflow: hidden;
  max-width: 520px;
  font-family: var(--mono);
  font-size: 11.5px;
  box-shadow: var(--shadow);
}
.term-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
  background: rgba(148, 163, 184, 0.04);
}
.term-head i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #334155;
}
.term-head i:first-child {
  background: #f87171;
}
.term-head i:nth-child(2) {
  background: #fbbf24;
}
.term-head i:nth-child(3) {
  background: #34d399;
}
.term-head span {
  margin-left: 8px;
  color: var(--mute);
  font-size: 10.5px;
}
.term-body {
  padding: 11px 14px;
  line-height: 1.85;
  color: #9fb0c9;
}
.t-dim {
  color: #475569;
}
.t-cyan {
  color: #67e8f9;
}
.t-green {
  color: #6ee7b7;
}
.t-amber {
  color: #fbbf24;
}

.right {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.login-card {
  width: 100%;
  max-width: 392px;
  padding: 30px 30px 24px;
}
.login-card h2 {
  font-size: 22px;
  margin: 6px 0 4px;
  letter-spacing: -0.01em;
}
.sub {
  color: var(--mute);
  font-size: 12.5px;
  margin: 0 0 22px;
}
form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.err {
  font-size: 12.5px;
  color: #fca5a5;
  background: var(--red-dim);
  border: 1px solid rgba(248, 113, 113, 0.28);
  padding: 8px 11px;
  border-radius: 8px;
}
.submit {
  height: 42px;
  margin-top: 4px;
  width: 100%;
  font-size: 14px;
}

.divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 22px 0 14px;
  color: var(--mute);
  font-size: 10.5px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}
.divider::before,
.divider::after {
  content: "";
  flex: 1;
  height: 1px;
  background: var(--border);
}

.demos {
  display: flex;
  gap: 10px;
}
.demo {
  flex: 1;
  text-align: left;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: rgba(148, 163, 184, 0.04);
  color: var(--text);
  cursor: pointer;
  font-family: inherit;
  transition: 0.15s;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.demo:hover {
  border-color: rgba(34, 211, 238, 0.4);
  background: rgba(34, 211, 238, 0.06);
}
.demo b {
  font-size: 12px;
}
.demo code {
  font-family: var(--mono);
  font-size: 10.5px;
  color: #67e8f9;
}
.demo span {
  font-size: 10.5px;
  color: var(--mute);
}

.foot {
  font-size: 10.5px;
  letter-spacing: 0.12em;
  color: var(--mute);
  text-transform: uppercase;
}

@media (max-width: 1024px) {
  .login {
    grid-template-columns: 1fr;
    padding: 40px 24px;
  }
  .left {
    display: none;
  }
  .right {
    margin: 0 auto;
    width: 100%;
  }
}
</style>
