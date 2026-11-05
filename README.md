<div align="center">
  <h2>JudgeX</h2>

  <p>
    <a href="https://github.com/Xiangyfff/JudgeX/stargazers"><img src="https://img.shields.io/github/stars/Xiangyfff/JudgeX?style=flat-square" alt="GitHub Stars"></a>
    <img src="https://img.shields.io/badge/Java-17-E76F00?style=flat-square" alt="Java 17">
    <img src="https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F?style=flat-square" alt="Spring Boot 3.5.16">
    <img src="https://img.shields.io/badge/Vue-3-42B883?style=flat-square" alt="Vue 3">
    <img src="https://img.shields.io/badge/H2-2.3-09476B?style=flat-square" alt="H2">
    <img src="https://img.shields.io/badge/Redis-7-DC382D?style=flat-square" alt="Redis 7">
    <img src="https://img.shields.io/badge/RocketMQ-5.3.1-D77310?style=flat-square" alt="RocketMQ 5.3.1">
    <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="MIT License"></a>
  </p>
</div>

<div align="center">

一个把不可信代码关进沙箱的 <strong>在线判题平台</strong>。

判题失败从来不是"算错了"，而是"跑飞了""重复跑了""跑完没人知道"。

</div>

## 项目预览

**题库与进度**

![JudgeX 题库](docs/screenshots/02-problems.png)

**做题与逐用例判题**

![JudgeX 判题结果](docs/screenshots/15-accepted.png)

**运行监控**

![JudgeX 运行监控](docs/screenshots/07-dashboard.png)

**故障降级**

![JudgeX 消息通道降级](docs/screenshots/09-dashboard-degraded.png)

用户登录后从题库选题，在右侧编辑器里写代码，提交后不用等待：每个测试点跑完就点亮一个，通常 1.2 秒内看到最终判定。监控台把队列深度、节点心跳、判题耗时和消息通道摊在同一页，停掉 broker 也不会挡住判题——事件留在发件箱里，broker 回来自己追平。

## 核心功能

判题系统的难点不在"运行代码"，而在运行的是**不可信代码**、以及跑它的机器**随时会挂**。JudgeX 的设计围绕这两件事展开，可以概括为四层能力。

### 🧱 不丢不重的任务调度

> 提交即落库，任务靠一条 CAS 抢，跑一半的机器挂了有人捡回来。

- **数据库即队列** — 提交记录与判题任务在同一事务写入，调度器每 250ms 拉一批，不额外引入消息中间件。
- **CAS 认领** — 用一次条件更新把任务从 `QUEUED` 改成 `ASSIGNED`，affected rows 为 1 的 Worker 拿走任务，多实例部署也安全。
- **租约与看门狗** — 心跳每 3 秒续一次 45 秒租约；看门狗每 5 秒扫过期任务，崩溃节点上的任务 50 秒内被重新投递，连续 3 次失败收敛为系统错误。
- **三层幂等** — 请求幂等键拦重复点击，CAS 拦重复派发，结果表在（提交，用例序号）上的唯一索引拦"任务回收后迟到的结果"。

### 🔒 分层沙箱

> 先管住一定会发生的事：死循环、输出洪水、内存。

- **超时强杀** — 到点销毁整棵进程树，提交的代码 fork 出子进程也带得走。
- **输出截断** — stdout / stderr 各一个读取线程，累计超过 64KB 立刻杀进程判 `OLE`，避免子进程堵死在管道上假装超时。
- **资源与环境保护** — Java、Node 有堆上限；环境变量清空只留 `PATH`，`HOME` 与临时目录指向任务私有目录，跑完即删。
- **Linux 级隔离兜底** — 切到 Docker 后端后由 namespace、只读根与 `--pids-limit` 兜底；`local` 模式明确只做到进程级，防误伤不防恶意。

### 📡 看得见的判题过程

> 一次提交 1.2 秒，用户不该对着转圈猜。

- **逐用例推送** — 每跑完一个用例发一条 SSE，前端把 chip 依次点亮，不轮询。
- **快照 + 增量** — 断线重连先收当前全量结果再收增量，刷新页面不会永久丢一段。
- **运行指标** — 队列深度每 5 秒采样、节点心跳、端到端 P50、语言运行时探测，都落在监控台。

### 📤 不丢的事件投递

> 结果落库和事件发出，必须是同一个事实。

- **事务性发件箱** — 终态与 outbox 行同事务提交，`event_key` 唯一索引兜底；Relay 每 400ms 领取到期行投递 RocketMQ。
- **退避与死信** — 失败按 1s → 2s → 4s 退避（30s 封顶），6 次仍失败进死信表，留给人看。
- **消费幂等** — 通知表在提交号上有唯一索引，重复投递不会多出一条。
- **故障演练** — 停掉 broker 提交，判题照常 AC、待投递变 1；重启 broker 不到半分钟自动追平。

## 系统流程

```mermaid
sequenceDiagram
    autonumber
    actor User as 用户
    participant Web as Vue 工作台
    participant API as Spring Boot
    participant DB as H2
    participant Worker as 判题 Worker
    participant Box as 沙箱

    User->>Web: 写代码并提交
    Web->>API: 提交请求（带幂等键）
    API->>DB: 提交与任务同事务落库
    API-->>Web: 返回提交号，SSE 开始推送

    Worker->>DB: CAS 认领任务（租约 45s）
    Worker->>Box: 编译并逐个用例执行
    Box-->>Worker: 输出 / 超时 / 内存 / 溢出
    Worker->>DB: 用例结果落库（唯一索引兜底）
    Worker-->>Web: SSE 推送 case 事件
    Worker->>DB: 终态与 outbox 同一事务提交

    Note over DB,API: Relay 每 400ms 领取到期事件投递 RocketMQ，消费端落站内通知
```

## 技术栈

| 层次 | 技术 | 用途 |
| :--- | :--- | :--- |
| Web | Vue 3、Vite、TypeScript、SSE | 题库、编辑器、逐用例进度与监控台 |
| API | Java 17、Spring Boot 3.5.16、JdbcTemplate | 鉴权、提交、调度与 REST API |
| 执行 | ProcessBuilder、Docker | 编译运行与进程级隔离，两套沙箱共用一套接口 |
| 数据 | H2（文件模式） | 题目、提交、任务、租约与发件箱 |
| 异步与缓存 | RocketMQ 5.3.1、Redis 7 | 结果事件投递；限流、节点存活与排行榜 |
| 观测 | Micrometer 风格指标、SSE | 队列深度、心跳、吞吐与端到端耗时 |

## 本地运行

### 环境要求

| 组件 | 要求 | 说明 |
| :--- | :--- | :--- |
| JDK | 17 | 后端运行环境，唯一必需项 |
| Node.js | 22 | 仅在需要重新构建前端时使用 |
| Redis | 7 | 可选，缺失时自动降级到内存实现 |
| Docker | 可选 | 跑 RocketMQ，或把沙箱切换到容器隔离 |

### 1. 启动后端

```bash
cd backend
java -jar target/judgex-backend-1.0.0.jar
```

访问 `http://127.0.0.1:18900`，用演示账号登录：

| 账号 | 密码 | 角色 |
| :--- | :--- | :--- |
| `student` | `student123` | 学生 |
| `chen` | `student123` | 学生（用于对比"全部提交"） |
| `admin` | `admin123` | 管理员，多一个题库管理页 |

首次启动自动建库并写入 6 道题、每题 5 个测试点。数据在 `backend/data/`，删除该目录即重置。

端口默认 18900：本机 8080、3306、6379 已被其他项目占用，需要修改时设置 `JUDGEX_PORT`。

### 2. 可选中间件

Redis 与 RocketMQ 都不是必需的，不启动只会少一层加速或事件投递，判题链路不受影响。

```bash
# Redis
redis-server --port 6379

# RocketMQ（自建 nameserver + broker，端口 19877 / 10913）
docker network create judgex-mq
docker run -d --name judgex-mq-namesrv --network judgex-mq -p 19877:9876 apache/rocketmq:5.3.1 sh mqnamesrv
docker run -d --name judgex-mq-broker --network judgex-mq -p 10913:10913 \
  -v "$PWD/docker/rocketmq/broker.conf:/home/rocketmq/broker.conf" \
  apache/rocketmq:5.3.1 sh mqbroker -c /home/rocketmq/broker.conf
```

### 3. 从源码构建

```bash
cd frontend
node node_modules/vite/bin/vite.js build

cd ../backend
rm -rf src/main/resources/static && mkdir -p src/main/resources/static
cp -r ../frontend/dist/. src/main/resources/static/

/path/to/mvn -o -B -DskipTests -Daether.syncContext.named.factory=noop package
java -jar target/judgex-backend-1.0.0.jar
```

### 常见问题

| 现象 | 处理方式 |
| :--- | :--- |
| 端口被占用 | 设置 `JUDGEX_PORT`，或先释放 18900 |
| 控制台告警 Redis 连接失败 | 属正常降级，限流走内存、排行榜返回空；需要时再启动 Redis |
| 监控台消息通道显示 DEGRADED | broker 不可用，事件留在发件箱；broker 恢复后自动追平 |
| 打包报 `Unable to rename ... jar.original` | 先停掉正在运行的 jar，Windows 文件占用会导致重命名失败 |
| 沙箱里创建子进程报 `CreatePipe error=5` | 权限受限，需要以具备进程创建权限的方式运行后端 |

## 目录结构

```text
JudgeX
├── backend/                 # Spring Boot 单体 + 判题 Worker
│   └── src/main/java/com/judgex/
│       ├── auth/            # JWT、密码哈希、请求过滤
│       ├── repo/            # 全部 JdbcTemplate
│       ├── sandbox/         # local / docker 两套实现
│       ├── service/         # 判题引擎、调度、SSE、发件箱
│       ├── redis/           # 限流、存活键、排行榜
│       └── mq/              # producer、relay、消费端
├── frontend/                # Vue 3 工作台
│   └── src/
│       ├── views/           # 登录、题库、做题、记录、监控、管理
│       └── components/      # 编辑器、外壳、弹窗、状态药丸
├── docker/rocketmq/         # broker 配置
└── docs/                    # 架构说明与截图
```

## 已知限制

- `local` 沙箱是进程级隔离：防得住超时、输出洪水与误写文件，防不住读内存、扫内网、提权。生产环境应切到 `docker`。
- local 模式下仅 Java 与 Node 设了内存上限，Python、C++ 没有；内存用量也拿不到准确值，页面显示 `—` 而不是编造数字。
- 调度为单节点扫表。多节点下 CAS 仍然安全，但回收扫描会重复，替换为 `SKIP LOCKED` 或独立租约表更合适。
- 限流按用户维度，多实例依赖 Redis 共享配额；内存回退版只在单机准确。
- H2 为单文件库，适合演示与单机部署。迁移 MySQL 只需调整数据源与方言，SQL 中没有 H2 专有语法。

## License

本项目基于 [MIT License](LICENSE) 开源。
