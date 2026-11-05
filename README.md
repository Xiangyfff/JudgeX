# JudgeX

在线判题系统。浏览器里写代码、提交，几秒内逐个测试点出结果。

做它的起因很朴素：判题这件事看着是"跑一下别人写的代码"，真正的麻烦全在另外两个地方——别人的代码不听话，以及机器自己会挂。所以这个 README 里功能占的篇幅不多，大部分在讲这两件事是怎么兜住的。

目前能用的东西：Java / Python / JavaScript 三种语言、6 道题、逐用例实时进度、提交记录、一个给运维看的监控台。后端 Spring Boot 单体，前端 Vue 3，前端构建产物直接打进 jar，一个端口跑完。

先把丑话说在前面：这是单机项目，`local` 沙箱只做到进程级隔离，别拿它当生产判题机用。

![做题页](docs/screenshots/15-accepted.png)

## 跑起来

前置只要 JDK 17，不用装 MySQL，不用装 Docker。

```bash
cd backend
java -jar target/judgex-backend-1.0.0.jar
```

然后开 <http://127.0.0.1:18900>。

| 账号 | 密码 | 能干什么 |
| --- | --- | --- |
| `student` | `student123` | 做题、提交、看自己的记录 |
| `chen` | `student123` | 同上，用来对比"全部提交" |
| `admin` | `admin123` | 多一个「题库管理」 |

第一次启动会自己建库，灌 6 道题、每题 5 个测试点。数据落在 `backend/data/`，想从头再来直接删掉这个目录。

端口用 18900 而不是 8080，纯粹是因为我这台机器上 8080、3306、6379 都被别的项目占着，懒得关，就挑了个没人用的。要改设 `JUDGEX_PORT`。

Redis 和 RocketMQ 都是可选的。没有 RocketMQ，判题照常跑，事件会留在表里等 broker 回来；没有 Redis，限流退回进程内、在线节点退回数据库心跳、排行榜返回空列表，功能不缺，只是少了个加速层。装法见 `docker/rocketmq/broker.conf` 和文末的配置表。

## 它是怎么跑起来的

```
提交 → 落库 → 排队 → 拉取 + 租约 → 沙箱执行 → 逐用例 SSE 回传
```

下面是几个我反复改过、也觉得值得讲的地方。

### 队列就是一张表

提交进来先落库：`submissions` 一行 `PENDING`，`judge_tasks` 一行 `QUEUED`，同一个事务。调度器每 250ms 从表里拉一批。

我最早是想上 MQ 做这个队列的，写到一半停下来了。原因是任务的状态机、租约、幂等键本来全在数据库里，队列一搬到别处，"这个任务现在到底在哪、什么状态"就有了两个真相，出故障时对账比原来更麻烦。而这个项目的瓶颈是 CPU 秒，不是消息吞吐，MQ 换不来什么。

所以 MQ 在这个项目里只用在它真正合适的地方——判题结果发出去之后的下游分发，见下面「判题结果怎么送出去」。

### 抢任务是一条 UPDATE

```sql
UPDATE judge_tasks
   SET status='ASSIGNED', worker_id=?, attempts=attempts+1, version=version+1
 WHERE id=? AND status='QUEUED' AND version=?
```

affected rows 等于 1 的那个 worker 拿到任务，其余的空手回去下一轮。不用加锁，起第二个 worker 也是安全的。

用例结果表在 `(submission_id, ordinal)` 上有唯一索引。任务被回收重跑、原来的 worker 又活过来补写结果时，重复的那条会被数据库直接挡掉，不指望应用层记得去重。

### worker 挂了，任务会被捡回来

worker 每 3 秒续一次租约，把 `lease_until` 推到 45 秒后。看门狗每 5 秒扫一遍过期的 `ASSIGNED` / `RUNNING`：状态退回 `QUEUED`，`attempts + 1`；累计 3 次还没跑完，任务进死信，提交判 `SYSTEM_ERROR` 并把原因写进去。

45 秒这个数没什么玄机，就是"心跳间隔的 15 倍"——够扛住一次 GC 停顿或网络抖动，又不至于让真挂掉的节点占着任务太久。

心跳同时往 Redis 写一份（`SET EX 10`），监控页靠它显示哪些节点活着。但租约的真相在数据库里，Redis 挂了不影响任务回收。

### 沙箱我只管三件事

死循环、输出洪水、内存。

- **超时**：`Process.waitFor(timeout)` 到点 `destroyForcibly()`，杀的是整棵进程树，不然提交的代码 fork 个子进程就赖着不走了。Docker 模式下直接杀容器。
- **输出**：stdout / stderr 各起一个线程读，累计超过 64KB 立刻杀进程判 `OLE`。不能等管道写满再发现——那时候子进程已经堵死在 write 上，看起来像超时。
- **内存**：Java 加 `-Xmx256m -Xss64m`，Node 加 `--max-old-space-size=256`。Python 和 C++ 在 local 模式下没有上限，这是我留的缺口，写在「已知问题」里了。

另外跑之前会把环境变量清空（只留 `PATH`），`HOME`、`TMPDIR` 指到任务自己的临时目录，跑完删掉——省得提交的代码从环境变量里把宿主机的路径读出来。

说清楚边界：`local` 是进程级隔离，防误伤够用，防不住恶意代码读内存、扫内网、提权。要真隔离，起 Docker 后把 `JUDGEX_SANDBOX` 设成 `docker`，`DockerSandbox` 走的是同一套接口。

### 判题过程得看得见

每跑完一个用例推一条 SSE，前端把 chip 一个个点亮，不轮询。

断线重连时先收一份 `snapshot`（当前所有结果），再收增量。这样中间丢几条也不会错位——如果只发增量，刷新一下就永远缺一块。

### Redis 只放能重建的东西

三件事，三件都可以丢：

| 用途 | 做法 | 挂了会怎样 |
| --- | --- | --- |
| 提交限流 | Lua 脚本一次做完"取令牌 → 补令牌 → 写回 → 续期" | 退回进程内 60s 滑窗，单机语义一致 |
| 节点存活 | `SET ... EX 10`，每 3 秒覆盖，在线列表用 `SCAN` | 退回数据库心跳，判题照跑 |
| 排行榜 | ZSET，`score = 通过题数 * 1e6 + (999999 - 总耗时ms)` | 返回空列表，不挡任何请求 |

把"通过题数优先、其次比谁快"压进一个 score 里，读的时候一次 `ZREVRANGE` 就够了，不用在应用层再排一次。

题库没有做缓存。6 道题，详情就是一次主键查询，没有热点，加一层缓存只会多一个失效点。

### 判题结果怎么送出去

判题完成和"发一条事件"必须是同一个事实。不然只有两种结局：结果落库了、事件丢了，下游永远不知道；或者事件发出去了、事务回滚了，下游收到一条不存在的结果。

所以 `finish()` 在一个事务里干两件事：更新 `submissions`，往 `outbox` 插一行，`event_key` 上有唯一索引兜底。之后 `OutboxRelay` 每 400ms 扫一遍到期的行，投到 RocketMQ：成功置 `SENT`，失败 `attempts+1` 然后退避 1s → 2s → 4s（30s 封顶），6 次还不行进 `DEAD`，留给人看。

消费端 `judgex-notify` 把事件落成站内通知，`notifications.submission_id` 上的唯一索引保证重复投递不会多出一条。

这套东西值不值得，我拿停 broker 试了一次：停掉之后提交，判题照常 AC，`outbox.pending` 变成 1；重启 broker 不到半分钟自动追平。整条判题链路没有一步在等 MQ——这是我最想要的性质。

## 目录

```
backend/
  src/main/java/com/judgex/
    auth/        JWT、密码哈希、请求过滤器
    repo/        全部 JdbcTemplate，没有 JPA
    sandbox/     local / docker 两套实现 + 路由
    service/     判题引擎、调度、进度推送、发件箱
    redis/       限流、存活键、排行榜
    mq/          producer、outbox relay、消费端
    web/         控制器 + SPA 兜底
frontend/src/
  views/         登录、题库、做题、提交记录、监控、管理
  components/    编辑器、外壳、弹窗、状态药丸
  lib/           API 封装、Markdown、语法高亮、格式化
docs/
  ARCHITECTURE.md   更细的流程和取舍
  screenshots/
docker/rocketmq/    broker 配置（自建 nameserver + broker）
```

架构上是个单进程：HTTP/JSON + SSE 进来，`SubmissionService` 做幂等，`SchedulerService` 拉任务，`JudgeEngine` 编译逐用例跑，`ProgressHub` 往外 fan-out。数据在 H2，沙箱在进程里或 Docker 里。

没拆微服务。这个体量拆开，多两个进程、多几次网络往返，换不来实际收益。真正需要独立扩容的只有判题节点：换个 `JUDGEX_WORKER_ID` 起第二个就行，互斥靠上面那条 CAS。

## 接口

`/api/**` 都要 `Authorization: Bearer <token>`。SSE 例外，`EventSource` 塞不了 header，所以支持 `?token=`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | 登录，返回 token |
| GET | `/api/problems` | 题目列表（带通过率） |
| GET | `/api/problems/{id}` | 题目详情，只回可见样例 |
| POST | `/api/submissions` | 提交，`Idempotency-Key` 可选 |
| GET | `/api/submissions/{id}/events` | SSE：`snapshot` / `status` / `compiled` / `case` / `done` |
| GET | `/api/dashboard` | 队列曲线、节点、吞吐 |
| GET | `/api/leaderboard` | 排行榜 |
| GET | `/api/mq/stats` | 发件箱统计 |
| GET | `/api/health` | 健康检查，免鉴权 |

完整列表（含管理端）在 `docs/ARCHITECTURE.md`。

## 配置

都能用环境变量覆盖，默认值在 `backend/src/main/resources/application.yml`。

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `JUDGEX_PORT` | `18900` | 服务端口 |
| `JUDGEX_SANDBOX` | `local` | `local` / `docker` / `auto` |
| `JUDGEX_REDIS_ENABLED` | `true` | 关掉走内存实现，功能不变 |
| `JUDGEX_MQ_ENABLED` | `true` | 关掉事件留在 outbox，判题不受影响 |
| `JUDGEX_MQ_NAMESRV` | `127.0.0.1:19877` | 我本机自建的 nameserver |
| `JUDGEX_WORKER_ID` | `node-1` | 节点标识 |
| `JUDGEX_WORKER_CONCURRENCY` | `4` | 并行判题槽位 |
| `JUDGEX_JWT_SECRET` | 开发默认值 | 上线必须换 |

## 我没做的，和已知的问题

写在这里，省得别人踩。

- `local` 沙箱防不住恶意代码。它能防超时、输出洪水、误写文件；防不住读内存、扫内网、提权。要真跑不可信代码，切 `docker`。
- Python 和 C++ 在 local 模式下没有内存上限，内存用量也拿不到准确值，页面上显示 `—` 而不是编一个数字。
- 调度是单节点扫表。多节点时 CAS 本身还是安全的，但回收扫描会重复扫，换 `SKIP LOCKED` 或独立租约表更合适。
- 限流按用户，不是全局。多实例靠 Redis 版共享配额，内存回退版只在单机准。
- 排行榜只算"通过题数 + 最快 AC 总耗时"，没有赛季、分段、冻结榜。
- 站内通知只是落库，没有邮件/短信通道。
- H2 是单文件库，适合演示和单机。换 MySQL 只改 datasource 和方言，SQL 里没写 H2 专有语法。

## 从源码构建

在离线环境里验过，Maven 要指到本地仓库。

```bash
cd frontend
node node_modules/vite/bin/vite.js build

cd ../backend
rm -rf src/main/resources/static && mkdir -p src/main/resources/static
cp -r ../frontend/dist/. src/main/resources/static/

/path/to/mvn -o -B -DskipTests -Daether.syncContext.named.factory=noop package
java -jar target/judgex-backend-1.0.0.jar
```

两个坑记一下，都真实踩过：

- **Windows 上打包前必须先停掉正在跑的 jar**，否则 repackage 那步 rename 会失败，报 `Unable to rename ... jar.original`。
- **`-Daether.syncContext.named.factory=noop`** 是绕开沙箱里拿不到写锁时的报错，正常机器不需要。

## License

MIT。
