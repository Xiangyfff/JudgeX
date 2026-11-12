# 沙箱实测报告

这份报告记录两种沙箱（`local-process` 与 `docker`）在真实机器上的行为：哪些限制确实生效、哪些只是写在文档里、哪些边界会出问题。所有数字都是本机实测，不是估算。

## 测试环境

| 项 | 值 |
| :--- | :--- |
| 操作系统 | Windows 11 家庭版 26200 |
| CPU / 内存 | AMD Ryzen 9 7940HX（16 核 32 线程）/ 15.2 GB |
| JDK | OpenJDK 17.0.20.1 |
| Python / Node | 3.8.5 / v24.22.0 |
| 数据库 | MySQL 8.0.45 |
| 消息 | RocketMQ 4.9.7（本机 broker） |
| Docker | Docker Desktop 29.8.2（容器模式），镜像 `judgex-runner:latest`（772 MB） |
| 沙箱模式 | 先 `local-process`，后 `docker`（两轮，同一台机器、同一组用例） |
| Worker 槽位 | 4 |
| 题目配置 | 时限 2000 ms、内存 256 MB、输出上限 65536 字节 |

## 测试方法

通过 HTTP API 用 `student` 账号提交代码，等判定结束后从 `test_results` 读取每个用例的判定、耗时与 stdout。脚本在仓库外（`E:\dsh-try\tmp\sandbox-probe.ps1`、`sandbox-probe-container.ps1`），可重跑；两轮测试之间只切换 `judgex.sandbox.mode`，其余配置不变。

「端到端」= 提交到出判定；「单用例」= 该用例实际执行毫秒数（不含排队与编译）。

## 一、判定正确性

| 用例 | 期望 | 实测 | 端到端 | 单用例耗时 |
| :--- | :--- | :--- | :--- | :--- |
| 正常 a+b（基线） | AC | **AC** | 751 ms | 114～120 ms |
| `while True: pass` | TLE | **TLE** | 10.2 s（5 用例 × 2 s） | 2022～2027 ms |
| `time.sleep(2.3)` | TLE | **TLE** | 10.2 s | 2022～2025 ms |
| 无限往 stdout 写 | OLE | **OLE** | 639 ms | 109～120 ms |
| 除零 | RE | **RE** | 704 ms | 117～122 ms |
| Java 持续分配内存 | MLE | **MLE** | 2.68 s | 290～323 ms |
| Java 少写分号 | CE | **CE**（中文乱码，见修复 2） | 797 ms | — |
| Python 语法错误 | CE | **RE**（当时无编译步骤，见修复 3） | 678 ms | 115～128 ms |

时限掐得很准：题目 2000 ms，五个用例全部落在 2022～2027 ms（多出来的 20 多毫秒是杀进程与回收的固定开销）。

## 二、边界值

| 用例 | 期望 | 实测 | 端到端 |
| :--- | :--- | :--- | :--- |
| 输出 63000 字节（低于 65536 上限） | 不判 OLE | **WA**，正常读完 | 766 ms |
| `time.sleep(1.8)`（低于 2000 ms） | 不判 TLE | **WA**，用例 1918～1934 ms | 9.8 s |
| Java 分配约 200 MB（低于 256 MB 上限） | 不判 MLE | **WA**，正常输出 | 2.42 s |
| 源码约 70 KB（超过 64 KB 上限） | 拒绝 | **400 `source exceeds 65536 bytes`** | 未入队 |

边界是准的：超一点就杀，差一点就放过。

## 三、隔离性（这一组是反向测试，期望「拒绝」）

| 用例 | 期望 | 实测 | 端到端 |
| :--- | :--- | :--- | :--- |
| 读 `C:\Windows\win.ini` | 读不到 | **读到了**，stdout 前 40 字符是 `; for 16-bit app support` | 675 ms |
| 连 `127.0.0.1:3306`（MySQL） | 连不上 | **连上了**，5 个用例都打印 `connected` | 933 ms |
| 打印环境变量 | 只剩必要项 | `HOME, LANG, LC_ALL, PATH, SYSTEMROOT, TEMP, TMP, TMPDIR` | 673 ms |
| 打印当前目录 | 独立目录 | `E:\dsh-try\data\work\sub-55` | 764 ms |
| 往工作目录写文件 | 允许 | 写入成功 | 719 ms |
| 起子进程后父进程立刻退出 | 子进程被清理 | **子进程存活 25 秒；单用例从 0.2 秒拖到 4.35 秒；工作目录残留** | 21.9 s |

前两条是 `local-process` 模式的已知短板（`docs/ARCHITECTURE.md` 的威胁表里已标注「local 不挡」），这次拿到了证据：本机模式只能保证**判题结果可信**，不能保证**宿主机安全**。

第三、四条说明环境清理是有效的：只有一个 `SYSTEMROOT` 是 Windows 自己注入的，其余变量都被清掉了。

## 四、并发与清理

| 用例 | 结果 |
| :--- | :--- |
| 同一用户连发两条 | 第二条立刻 **429 `RATE_LIMITED`**：`you already have a submission in flight; wait for it to finish` |
| 三个用户同时各发一条 | 1.15 s 内全部受理，5.08 s 全部判完；单条 total 约 3.6 s（5 用例 × 0.5 s 睡眠），串行需要约 11 s |
| 工作目录清理 | 修复前残留 1 个（被子进程占用）；修复后连续多轮提交，残留 0 个 |

三用户并发这条说明 4 个槽位是真的在并行跑，不是串行排队。

## 五、实测发现的问题与修复

### 修复 1：子进程逃逸

- **现象**：代码里 `subprocess.Popen` 起一个子进程后自己立刻退出，判定结束后子进程还在跑；同时因为它继承了 stdout 管道，读线程等不到 EOF，单用例从 0.2 秒被拖到 4.35 秒，工作目录也因为被子进程占用而删不掉。
- **原因**：`destroyForcibly()` 只杀直接子进程，不管进程树；Windows 上父进程退出后子进程会被重新挂靠，并不会跟着死。
- **修复**：在执行期间每 100 ms 采样一次 `ProcessHandle.descendants()`，执行结束（正常结束、超时、输出超限）时按记录把整棵树杀掉。
- **效果**：单用例 4.35 s → 0.42 s；残留进程 0；工作目录恢复正常清理。
- **代价**：TLE 判定从 2022 ms 变成 2084 ms（多约 60 ms），OLE 从 115 ms 变成 255 ms——都是用时间换「杀干净」。

### 修复 2：编译错误信息乱码

- **现象**：Java 编译失败时，用户看到的是 `Main.java:3: ????: ???';'`。
- **原因**：中文 Windows 上 `javac` 用本地编码输出诊断信息，被判题进程按 UTF-8 读取后变成问号。
- **修复**：编译命令加 `-J-Duser.language=en -J-Duser.country=US`，强制英文诊断。
- **效果**：同样代码现在显示 `Main.java:3: error: ';' expected` / `1 error`。

### 修复 3：Python / JavaScript 没有「编译错误」

- **现象**：Python 语法错误被判成 RE，和运行时异常混在一起。
- **修复**：Python 用 `ast.parse` 做一次语法预检，Node 用 `node --check` 预检；预检失败就是 CE。
- **效果**：Python 语法错 → **CE**（输出末尾仍是 `SyntaxError: invalid syntax`），JavaScript 语法错 → **CE**（`main.js:1 SyntaxError: Unexpected number`）；正常提交仍为 AC。
- **代价**：每次提交多一次进程启动，约 40～90 ms。

## 六、容器模式实测（`--judgex.sandbox.mode=docker`）

镜像 `judgex-runner:latest` 由三份官方镜像多阶段拷贝组成：`eclipse-temurin:17-jdk` 的 JDK、`node:20-slim` 的 node、`python:3.11-slim` 的 python3，统一放在 Debian bookworm 基础上（`docker/runner/Dockerfile`）。之所以不直接 `apt install`，是因为本机只能访问镜像仓库代理，容器内 `apt-get` / `apk` 全部失败（`APT_FAILED` / `APK_FAILED`）。

镜像内运行时：Python 3.11.18、Node v20.20.2、OpenJDK 17.0.21、`/opt/java/openjdk/bin/javac`。**镜像里没有 g++，容器模式下 C++ 提交会编译失败**，本轮只覆盖 Java / Python / JavaScript。

每个用例的执行参数：`--network none --memory 256m --memory-swap 256m --cpus 1.0 --pids-limit 64 --cap-drop ALL --security-opt no-new-privileges --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m`，工作目录以 `-v` 挂到 `/work`。

| 用例 | 期望 | 实测 | 端到端 | 单用例耗时 |
| :--- | :--- | :--- | :--- | :--- |
| 正常 a+b（Python） | AC | **AC** | 4.27 s | 397～417 ms |
| 正常 a+b（Java） | AC | **AC** | 4.92 s | 486～549 ms |
| 正常 a+b（JavaScript） | AC | **AC** | 3.03 s | 435～483 ms |
| `while True: pass` | TLE | **TLE** | 12.3 s | 2049～2059 ms |
| 无限往 stdout 写 | OLE | **OLE** | 3.34 s | 360～504 ms |
| 除零 | RE | **RE** | 4.02 s | 431～469 ms |
| Java 少写分号 | CE | **CE**（英文报错，见修复 2） | 2.24 s | — |
| Java 持续分配内存 | MLE | **MLE** | 5.27 s | 624～671 ms |
| Python 持续分配内存 | MLE | **MLE**（容器 OOM kill，见修复 4） | 5.37 s | 614～743 ms |
| 读 `C:\Windows\win.ini` | 拒绝 | **RE**，`FileNotFoundError` | 3.95 s | 404～440 ms |
| 连 `127.0.0.1:3306` | 拒绝 | **RE**，连接被拒/超时 | 5.64 s | 429～509 ms |
| 往 `/` 写文件 | 拒绝 | **RE**，`Read-only file system` | 4.52 s | 435～464 s |
| 往 `/tmp` 写文件 | 允许 | **WA**（程序正常跑完，输出 `ok`） | 3.67 s | 406～458 ms |
| fork 50 个子进程 | 受限 | **TLE**（`--pids-limit` 让 fork 失败，随后 sleep 超时） | 12.0 s | 2039～2053 ms |
| 起 `sleep 25` 子进程后立刻退出 | 随容器清理 | **WA**，容器结束后进程不存在 | 4.45 s | 496～534 ms |
| 打印当前目录 | 独立目录 | `/work` | 3.83 s | 411～438 ms |
| 源码约 70 KB | 拒绝 | **400 `source exceeds 65536 bytes`** | 未入队 | — |

三条最关键的对比：

| 能力 | `local-process` | `docker` |
| :--- | :--- | :--- |
| 读宿主机文件 | 能读到 `win.ini` | 读不到 |
| 连本机 3306 | 连得上 | 连不上 |
| 写根文件系统 | 能写 | 只读，报 `Read-only file system` |
| 单用例开销 | 114～120 ms | 400～550 ms（容器启动占大头） |

判题语义没有变：时限仍然卡在 2000 ms（实测 2049～2059 ms），输出上限仍然判 OLE，AC 仍然是 AC。代价是每次执行多 300～450 ms 的容器启动，5 个用例的提交从 0.7 秒变成 3～5 秒。

### 容器模式下修掉的四个问题

1. **容器里执行了宿主机路径**：`JudgeEngine` 注入的是 `@Primary` 的 `SandboxRouter`，它 `implements SandboxRunner` 却只转发了 `name/available/exec`，`container()` 走接口默认值 `false`，于是容器里仍然执行 `C:\...\python.exe`，报 `exec: ... executable file not found in $PATH`，所有提交都是 CE。修复：`SandboxRouter` 转发 `container()`；`Language` 记录同时保存本机（绝对路径）与容器（`python3` / `javac` / `node`）两套命令。
2. **超时杀不死容器**：原来只 `destroyForcibly()` 掉 `docker run` 客户端，容器还在跑、stdout 管道不关，读管道的线程永远返回不了 —— 一个 `while True: pass` 就能让提交卡在 RUNNING，后续提交全被 429。修复：每次执行给容器一个唯一 `--name`，超时先 `docker kill -s KILL <name>` 再杀客户端；stdout/stderr 都放到独立线程读，输出超限时也走同一条 kill 路径。
3. **输出洪水被误判成 TLE**：判定顺序是先 TLE 后 OLE，而杀进程的过程让 `timedOut` 也为真。修复：OLE 先于 TLE 判定；输出超限后立即跳出等待，不烧完整个时限（单用例从 5.4 秒降到 0.4 秒）。
4. **容器 OOM 被判成 RE**：Python 撑爆 256 MB 时被 OOM killer 干掉，退出码 137，原来只看 stderr 里有没有 `MemoryError`。修复：退出码 137 直接判 MLE。

## 七、仍然存在的限制

1. **本机模式不隔离文件系统和网络**：实测能读 `C:\Windows\win.ini`、能连本机 3306。要挡这两项必须用容器模式。
2. **容器模式仍是 root、没有 seccomp**：镜像默认以 root 运行，靠 `--cap-drop ALL` + `no-new-privileges` 收窄；没有配 seccomp profile，也没有 `--user` 非 root 账号。真要上线还得补这两项。
3. **容器模式没有 C++**：镜像里没有 g++，`cpp` 提交在容器模式下编译失败；本机模式可用（MinGW）。
4. **Python / C++ 在本机模式没有内存上限**：Windows 没有 `rlimit`，本机模式只有 Java（`-Xmx`）和 Node（`--max-old-space-size`）能按题目配置压住；容器模式对四种语言都靠 `--memory` 统一限制。
5. **内存峰值一栏仍是 `—`**：两种模式都没有采集实际 RSS，MLE 靠运行时自报 OOM（本机）或退出码 137（容器）。
6. **判定耗时受负载影响**：同一个 a+b，空闲时单用例 114～120 ms，机器忙时 185～212 ms；容器模式另有启动开销。报告里的毫秒数只用于横向比较。
7. **并发数据只有 3 个用户**：演示账号只有三个，更多用户的并发与限流没有压测数据。
8. **镜像需要本地构建**：`docker/runner/Dockerfile` 依赖能访问镜像仓库的代理（本机走 `docker.m.daocloud.io`），离线机器上要先准备好基础镜像。

## 结论

- **判题可信**：AC / WA / TLE / OLE / RE / CE / MLE 七种判定在两种模式下都有实测样本，时限与输出上限卡在毫秒级，边界值不会误判。
- **资源可控**：超时、超输出、超内存都能被杀掉；进程树能杀干净；容器有名字，超时杀掉的是容器本身而不是壳；工作目录会清理。
- **两种模式的定位**：`local-process` 只适合本机开发与演示，它保证的是「判题结果可信」，不是「宿主机安全」；`docker` 才补齐了文件系统、网络、内存三块隔离，代价是每用例多 300～450 ms。
- **还差的口子**：镜像非 root + seccomp、容器内 C++、真实内存峰值采集。这三项是上线前要补的。
