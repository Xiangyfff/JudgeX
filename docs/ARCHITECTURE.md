# JudgeX 架构说明

这份文档只讲**为什么这么写**，不复述代码。

---

## 1. 数据模型

10 张表（8 张核心表 + 事务发件箱与通知），SQL 全部写在 MyBatis 的 Mapper XML 里（没有 JPA——这个场景里查询形态固定，SQL 比 ORM 更好读也更好调）；表结构由 Flyway 的版本化脚本演进，启动时自动执行。

| 表 | 职责 | 关键约束 |
| --- | --- | --- |
| `users` | 账号、角色、PBKDF2 密码摘要 | `username` 唯一 |
| `problems` | 题面、时限、内存限制、难度、标签、发布状态 | `slug` 唯一 |
| `test_cases` | 测试点：输入、期望输出、分值、是否可见 | 索引 `(problem_id, ordinal)` |
| `submissions` | 一次提交：源码、语言、总判定、得分、耗时 | **唯一索引 `(user_id, idem_key)`** |
| `test_results` | 每个测试点的结果：判定、耗时、内存、实际输出 | 索引 `(submission_id, ordinal)` |
| `judge_tasks` | 判题任务：状态、持有者、租约、重试次数、最后一次错误 | 索引 `(status, id)` |
| `workers` | 节点心跳：最后心跳时间、并发上限、当前负载 | 主键 `worker_id` |
| `audit_log` | 管理端变更留痕 | — |

**为什么 `submissions` 和 `judge_tasks` 分开**：提交是"用户产生的事实"，任务"谁来跑、跑了几次"是调度内部状态。混在一张表里，一次重试就会污染用户的提交记录；分开之后，重试只动 `judge_tasks`，`submissions` 只在最终判定时写一次。

---

## 2. 状态机

```
                 enqueue                claim (CAS)
  PENDING ──────────────► QUEUED ──────────────────► ASSIGNED
                             ▲                          │
                             │  lease expired           │ start
                             │  (attempts < 3)          ▼
                             └──────────────────────  RUNNING
                                                        │
                        ┌───────────────────────────────┼──────────────────┐
                        ▼               ▼               ▼                  ▼
                   SUCCESS(AC)     WA/TLE/MLE/OLE    RE/CE        SYSTEM_ERROR
                        └───────────────┴───────────────┴──────────────────┘
                                   submission 终态，不再流转
```

- **状态流转用条件更新而不是版本号**：认领是 `UPDATE ... WHERE id = ? AND status = 'QUEUED'`，影响 0 行就说明被别人抢走了。没有 `version` 列，也就不存在读-改-写这种竞态窗口，多实例部署不需要额外加锁。
- **`SUCCESS` 与 `AC` 的区别**：`SUCCESS` 是"任务跑完了"，`AC` 是"全部用例通过"。任务成功但答案错误，提交判定是 `WA`。
- 终态不可逆：一旦 `submissions.status` 落终态，后续任何事件都只做幂等忽略。

判定含义：`AC` 通过 · `WA` 答案错 · `TLE` 超时 · `MLE` 超内存 · `OLE` 输出超限 · `RE` 运行错误 · `CE` 编译错误 · `SYSTEM_ERROR` 平台自身故障。

---

## 3. 调度：拉模式 + 租约

```
Worker 循环（每 250ms）:
  1. 续租：UPDATE judge_tasks SET lease_until = now + 45s WHERE owner = me AND status IN (ASSIGNED, RUNNING)
  2. 有空闲槽位 → 捞一条 QUEUED → CAS 认领
  3. 认领成功 → 提交到判题线程池；失败 → 说明被别人抢了，什么都不做
  4. 每 3s 上报心跳到 workers
```

**为什么是拉不是推**：推模式下，调度器必须知道每个 Worker 的实时负载，一旦网络抖动就会"以为对方闲、其实对方满了"。拉模式下 Worker 自己知道有没有空槽，**负载信息天然准确**，调度器只需要提供"谁能抢到"的原子性。

**认领为什么用 CAS 而不是 `SELECT ... FOR UPDATE`**：CAS 只有一条 UPDATE，不需要长事务持锁；抢不到就是 0 行，语义天然幂等。多实例部署时也不需要额外的分布式锁。

**租约为什么比心跳长**：心跳 3s、租约 45s，比例 15:1。这样即使网络抖 10 秒、GC 停顿几秒，也不会误判 Worker 死亡——**误杀比晚杀代价大得多**（误杀会重复执行，晚杀只是晚几秒出结果）。

看门狗每 5s 扫一次过期租约：`attempts < 3` 就重新入队，否则 `deadLetter` 成 `SYSTEM_ERROR`。上限 3 次是为了让"必然失败"的任务尽快收敛，而不是在队列里无限循环。

---

## 4. 判题执行

```
取源码 → 校验大小/语言 → 建临时目录 → 写文件
   → 编译（Java/Python 编译期检查；C++ 调 g++）
   → 失败则 CE，直接结束
   → 逐用例：
        起子进程（stdin 喂输入）
        双线程读 stdout / stderr，超 64KB 截断
        waitFor(timeLimit) → 超时 destroyForcibly（含子进程树）
        比对输出（忽略行尾空白）
        写 test_results（重复执行由终态守卫拦在前面）
        推 SSE `case` 事件
   → 汇总：全过 AC，否则取第一个失败用例的判定；得分为通过用例分值之和
   → 清理临时目录
```

**编译与运行分开计时**：编译时间不计入单用例时限，否则 Java 这种要起 JVM 的语言会被系统性歧视。

**时限与内存来自题目**：每个用例执行前从 `problems.time_limit_ms` / `memory_limit_mb` 取数，Java 额外给 2 秒 JVM 启动宽限（宽限只加在超时判断上，不影响题目展示的时限）。`MLE` 由运行时自己报的 OOM（`OutOfMemoryError`、`JavaScript heap out of memory` 等）识别——`local` 模式拿不到准确的内存峰值，就只报确定的事。

**重放不会覆盖已出的结果**：开始判题与写入判定都是条件更新（`WHERE id = ? AND status <> 'DONE'`）。回收租约、重复投递、人工重跑带来的迟到结果影响 0 行，直接被丢弃：用户已经看到的判定不会被第二次执行改掉，测试点也不会追加成两份。

**逐用例推送**：`ProgressHub` 为每个 submission 维护一组 `SseEmitter`。判题线程 push 事件时，**先存一份事件快照**，新连进来的客户端立刻收到 `snapshot` 再收增量——避免"打开页面时前面几个用例已经跑完，界面永远缺一块"。

**源码只给作者看**：`GET /api/submissions/{id}` 与它的 `/events` 除了要求登录，还校验提交的 `user_id` 是否等于调用者（管理员除外），否则 403。id 是自增的，能猜到编号不代表有权看别人的代码。

---

## 5. 沙箱：七层里真正落地了几层

威胁模型与对策（`local` 模式的实际实现）：

| 威胁 | `local` 模式 | `docker` 模式（已实现，需 daemon） |
| --- | --- | --- |
| 死循环 / 睡死 | `waitFor(timeout)` + `destroyForcibly()` 杀进程树 | 容器 `--stop-timeout` + `docker kill` |
| 内存炸弹 | Java/Node 的堆上限取自题目配置；Python/C++ 无法在 Windows 上限制 | `--memory` + `--memory-swap` |
| fork bomb | 无法在 Windows 上限制（Linux 可 `ulimit -u`） | `--pids-limit` |
| 读宿主文件 | 工作目录隔离，但**不阻止**读绝对路径 | 只读根 + 仅挂载工作目录 |
| 写宿主文件 | 只写自己的临时目录 | `--read-only` + tmpfs |
| 联网 | **不阻止** | `--network none` |
| 提权 / 逃逸 | 不阻止（同用户态） | `--cap-drop ALL` + `no-new-privileges`（尚未配置 user namespace 与 seccomp profile） |
| 输出洪水 | 读取线程 64KB 截断，标记 `OLE` | 同左（管道在宿主侧截断） |

**这是一份诚实的边界表**：`local` 模式适合"自己的学生写自己的作业"，不适合公网。真正的隔离必须靠内核能力，而 Windows 上没有 cgroup/namespace，所以 `DockerSandbox` 按同一接口实现，切换只需 `JUDGEX_SANDBOX=docker`。

代码里的取舍：**先做"一定会发生"的防护（超时、内存、输出），再做"可能发生"的防护（文件、网络、提权）**。前者不做，平台五分钟就被一个 `while(true)` 打死；后者不做，是安全债，要在部署前还。

---

## 6. 幂等：三个不同的"重复"

| 重复来源 | 防护 | 失败后的表现 |
| --- | --- | --- |
| 用户连点两次提交 | `Idempotency-Key` 头 + `(user_id, idem_key)` 唯一索引 | 第二次返回第一次的记录，不重复判题 |
| 调度器重复派发 | `UPDATE ... WHERE status='QUEUED' AND version=?` | 只有一个 Worker 抢到 |
| Worker 超时被回收后原任务又跑完 | `test_results` 唯一索引 + 终态写入带 `WHERE status != 终态` | 后到的结果被丢弃，不覆盖 |

---

## 7. 可观测性

- **traceId**：`TraceFilter` 给每个请求生成/透传 `X-Trace-Id`，写进 MDC，日志前缀直接可见；响应头带回，前端报错时能对上。
- **队列深度**：`MetricsService` 每 5s 采样一次 `QUEUED/RUNNING` 数量，Dashboard 画最近 2 分钟的曲线——**排队的形状比排队的数字有用**：尖峰说明突发流量，持续高位说明容量不足。
- **Worker 心跳**：`workers.last_heartbeat`，Dashboard 显示"在线节点 / 并发容量 / 当前占用"。
- **判题耗时 P50**：端到端（提交 → 出结果），不是单用例耗时——用户感知的是前者。

---

## 8. 并发与限流

- 单用户同时最多 1 个活跃提交（`limits.max-active-per-user`），防止一个人刷满队列。
- 每分钟 20 次提交（`limits.submit-rate-per-minute`），**Redis 令牌桶**（Lua 原子执行）；Redis 不可用时回退进程内滑动窗口。
- Worker 并发 4（`JUDGEX_WORKER_CONCURRENCY`），等于同时最多 4 个沙箱进程。

**为什么限并发而不是限 QPS**：这个系统的瓶颈是"CPU 秒"，不是"请求数"。100 个 1ms 的请求不构成压力，4 个 2s 的判题就把机器吃满了。限流应该贴着资源走。

**为什么令牌桶而不是固定窗口**：固定窗口在边界处会放过 2 倍流量（59s 与 61s 各放满一次）。令牌桶按 `perMinute/60` 匀速补充，桶容量设成每分钟上限，突发最多一次满桶，之后必须等补充。

---

## 9. Redis：三个落点与降级策略

| 落点 | 结构 | 为什么用它 | Redis 不可用时 |
| --- | --- | --- | --- |
| 提交限流 | Hash `jx:rl:submit:{userId}` + Lua | "读令牌 → 补令牌 → 写回"必须原子，Lua 一次 RTT 完成；多实例天然共享配额 | 进程内 60s 滑窗（单机语义一致） |
| Worker 存活 | String `jx:worker:{id}` + TTL 10s | `SET EX` 把"存活"变成过期语义，不用写清理任务；每 3s 覆盖 | 回退 `workers.last_heartbeat` 时间戳 |
| 排行榜 | ZSET `jx:lb` + Hash `jx:lb:meta` | 排名与 Top-N 是 ZSET 原生能力，SQL 要窗口函数 + 全表排序 | 返回空列表，页面显示空态 |

**分数公式**：`score = 通过题数 * 1_000_000 + max(0, 999_999 - 总耗时ms)`。ZSET 只能按一个 score 排序，把"通过数优先、其次比谁快"编码进一个整数，就不用在应用层做二级排序。

**三条纪律**：

1. Redis 里只放**可重建的派生状态**。限流桶丢了只是少限一会儿；心跳丢了 3s 后补上；排行榜丢了在下一次 AC 时用 `bestAccepted`（每人每题取最快 AC）重算。
2. **Redis 不在写路径上**。任务状态、判题结果只写数据库；Redis 写失败不允许影响判题，`JudgeEngine` 里刷新排行榜是 `try/catch` 包住的旁路。
3. **统一降级窗口**。`RedisGate` 探测失败后 5s 内直接走兜底，避免每个请求都吃一次 800ms 连接超时。

**没做的事**：没有用 Redis 当队列（`judge_tasks` 的 CAS 认领已经能保证多 Worker 互斥，队列搬走只会把"一个真相"拆成两个）；也没有做题库缓存（6 道题、主键查询、无热点 key，缓存命中率无从谈起）。

---

## 10. 事务性发件箱：判题结果怎么离开系统

判题结果除了给提交者看，还要被下游消费（站内通知、统计、审计）。这里有个经典的"双写"问题：**写库和发消息不是一个事务**。

| 方案 | 故障时的表现 |
| --- | --- |
| 判完直接发消息 | 消息发了但事务回滚 → 下游收到幽灵结果；消息没发出去 → 结果永远没人知道 |
| 先写库，再发消息 | 写完库进程挂了 → 事件永久丢失，只能靠人肉对账 |
| **事务性发件箱** | 结果和事件同一个本地事务；投递是异步的、可重试的、可对账的 |

**实现**：

- `finish` 在 `@Transactional` 里做两件事：更新 `submissions`，`INSERT INTO outbox`（`event_key = verdict:{submissionId}`，唯一索引兜底幂等）。
- `OutboxRelay` 每 400ms 扫 `status='PENDING' AND next_attempt_at <= now`（最多 20 条），先 CAS 到 `SENDING` 并打 30s 租约——**relay 自己崩了，30s 后别人能接手**。
- 投递成功 → `SENT`；失败 → `attempts+1`，`next_attempt_at = now + min(30s, 1s << attempts)`；达到 6 次 → `DEAD`，等人工重放。
- 消费端 `judgex-notify` 写 `notifications`，`submission_id` 唯一索引让重复投递变成无害操作（at-least-once + 幂等 = 效果上的 exactly-once）。写库失败时返回 `RECONSUME_LATER`，由 broker 重投；只有**反序列化不了**的消息才记日志后丢弃——重投一百次也还是坏消息，不能让它堵住队列。

**验证过的故障场景**：停掉 broker → 提交 → 判题照常 AC、`pending=1`、`lastError="No route info of this topic"` → 重启 broker → 27s 后 `pending=0, sent+1`，通知生成。整个过程判题链路一次都没有等 MQ。

**边界**：relay 是单实例扫表，扫的是索引 `(status, next_attempt_at)`，单机几万条/天没压力；再大就该换 CDC（Debezium 读 binlog）或按 `id % N` 分片。`DEAD` 目前只有查询接口，没有自动重放——这是刻意的，死信必须有人看一眼。

---

## 11. 如果要做大，下一步是什么

1. **队列外置**：`judge_tasks` 换成 Redis Stream / RabbitMQ，数据库只存结果。现在是"数据库当队列"，单机几千 QPS 没问题，但会跟业务查询抢连接池。
2. **容器池预热**：冷启动 200–800ms，占单次判题时间的 10–40%。预热池 + 复用能把这段抹掉，代价是常驻内存。
3. **多节点**：`JUDGEX_WORKER_ID` 已经预留；把租约表换成 Redis TTL，看门狗改成分布式扫描即可。
4. **测试点分片**：一个提交的 20 个用例拆到多台机器并行跑，取最慢的那个作为总耗时。
5. **重判**：题目测试点变更后，把历史提交重新入队——`judge_tasks` 加一个 `reason` 字段就够。
