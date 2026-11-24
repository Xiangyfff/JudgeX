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

## 六、容器模式实测：加固前（`--judgex.sandbox.mode=docker`）

镜像 `judgex-runner:latest` 由三份官方镜像多阶段拷贝组成：`eclipse-temurin:17-jdk` 的 JDK、`node:20-slim` 的 node、`python:3.11-slim` 的 python3，统一放在 Debian bookworm 基础上（`docker/runner/Dockerfile`）。之所以不直接 `apt install`，是因为本机只能访问镜像仓库代理，容器内 `apt-get` / `apk` 全部失败（`APT_FAILED` / `APK_FAILED`）。

镜像内运行时：Python 3.11.18、Node v20.20.2、OpenJDK 17.0.21、`/opt/java/openjdk/bin/javac`。**本节的镜像里没有 g++，容器模式下 C++ 提交会编译失败**，所以这一轮只覆盖 Java / Python / JavaScript；后来在 Dockerfile 末尾补了一层 `apt-get install g++`（境外源换阿里云镜像后 1 分半装完），C++ 在 Linux 部署机上的复测见第十节。

本轮（加固前）每个用例的执行参数：`--network none --memory 256m --memory-swap 256m --cpus 1.0 --pids-limit 64 --cap-drop ALL --security-opt no-new-privileges --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m`，工作目录以 `-v` 挂到 `/work`，容器内是 root、套 Docker 默认 seccomp。加固后的参数、实测与性能复测见下一节。

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

判题语义没有变：时限仍然卡在 2000 ms（实测 2049～2059 ms），输出上限仍然判 OLE，AC 仍然是 AC。代价是每次执行多 300～450 ms 的容器启动（安静机器），5 个用例的提交从 0.7 秒变成 3～5 秒；宿主有负载时这个启动开销会涨到 700～800 ms，见第七节。

### 容器模式下修掉的四个问题

1. **容器里执行了宿主机路径**：`JudgeEngine` 注入的是 `@Primary` 的 `SandboxRouter`，它 `implements SandboxRunner` 却只转发了 `name/available/exec`，`container()` 走接口默认值 `false`，于是容器里仍然执行 `C:\...\python.exe`，报 `exec: ... executable file not found in $PATH`，所有提交都是 CE。修复：`SandboxRouter` 转发 `container()`；`Language` 记录同时保存本机（绝对路径）与容器（`python3` / `javac` / `node`）两套命令。
2. **超时杀不死容器**：原来只 `destroyForcibly()` 掉 `docker run` 客户端，容器还在跑、stdout 管道不关，读管道的线程永远返回不了 —— 一个 `while True: pass` 就能让提交卡在 RUNNING，后续提交全被 429。修复：每次执行给容器一个唯一 `--name`，超时先 `docker kill -s KILL <name>` 再杀客户端；stdout/stderr 都放到独立线程读，输出超限时也走同一条 kill 路径。
3. **输出洪水被误判成 TLE**：判定顺序是先 TLE 后 OLE，而杀进程的过程让 `timedOut` 也为真。修复：OLE 先于 TLE 判定；输出超限后立即跳出等待，不烧完整个时限（单用例从 5.4 秒降到 0.4 秒）。
4. **容器 OOM 被判成 RE**：Python 撑爆 256 MB 时被 OOM killer 干掉，退出码 137，原来只看 stderr 里有没有 `MemoryError`。修复：退出码 137 直接判 MLE。

## 七、容器加固（2026-11-18）

上一轮的容器档还是「root + 默认 seccomp」。这一轮把运行参数与 profile 补齐，只改沙箱参数与配置，**没有改判题逻辑**。本章统一按 **16 条用例**计数：`sandbox-probe-container.ps1` 里就是 16 个 `Probe`，早前文字里的「17 条」是笔误。

### 7.1 落点

| 文件 | 改动 |
| :--- | :--- |
| `docker/runner/seccomp.json`（新增） | `defaultAction: SCMP_ACT_ALLOW` + 19 条拒绝规则，默认 `errnoRet: 1`（EPERM） |
| `backend/src/main/java/com/judgex/sandbox/DockerSandbox.java` | `docker run` 增加 `--user`、`--security-opt seccomp=<abs>`、`--ipc none`、三条 `--ulimit`，并把 `no-new-privileges` 补成 `:true`；新增 `seccompProfile()` / `resolveSeccomp()`（优先 `judgex.sandbox.seccomp-profile`，否则把 jar 内 `/sandbox/seccomp.json` 物化到 `%TEMP%` 并缓存，缺失时回退 Docker 默认 profile） |
| `backend/src/main/java/com/judgex/config/JudgexProps.java` | `Sandbox` 新增 `runAsUser`、`seccompProfile` 两个字段（原 4 字段顺序不变） |
| `backend/src/main/resources/application.yml` | `run-as-user: ${JUDGEX_SANDBOX_USER:65534:65534}`、`seccomp-profile: ${JUDGEX_SECCOMP_PROFILE:}` |
| `backend/pom.xml` | `docker/runner/seccomp.json` 打进 `BOOT-INF/classes/sandbox/seccomp.json`，与工作目录无关 |

### 7.2 加固项与生效证据

| 项 | 参数 | 实测证据 |
| :--- | :--- | :--- |
| 非 root | `--user 65534:65534` | 容器内 `id` = `uid=65534(nobody) gid=65534(nogroup)`；`/work` 下新文件属主 nobody，宿主可删除，清理正常 |
| seccomp 拒绝清单 | 19 条规则，覆盖 mount/umount2/pivot_root/chroot/mount_setattr/move_mount/open_tree/fsopen/fsconfig/fsmount/fspick/open_by_handle_at/name_to_handle_at、unshare/setns、clone 的 8 个命名空间标志、clone3、init_module/finit_module/delete_module/kexec_load/kexec_file_load、ptrace/process_vm_readv/process_vm_writev/process_madvise/perf_event_open/bpf/userfaultfd/pidfd_getfd、add_key/keyctl/request_key、swapon/swapoff/reboot/acct、syslog/settimeofday/clock_settime/clock_adjtime/adjtimex/sethostname/setdomainname、io_uring_*、mq_*、fanotify_init | `docker inspect` 的 `SecurityOpt` 含完整 JSON；容器内实测 `unshare(CLONE_NEWUSER)`、`mount`、`ptrace`、`keyctl`、`clone(CLONE_NEWUSER)` 全部 `EPERM(1)`（输出见 7.4） |
| `clone3` → ENOSYS | `errnoRet: 38` | 特意不用 EPERM：glibc 先试 `clone3`，EPERM 不会回落到 `clone`，会让 `pthread_create` 失败；ENOSYS 走回落，Java/Node/Python 线程正常 |
| `--ulimit nofile=64:64` | 64 | `docker inspect` 的 `Ulimits` Soft/Hard 均为 64 |
| `--ulimit core=0` | 0 | 不产生 core dump |
| `--ulimit fsize=67108864` | 64 MB | 单文件上限；`/work` 是宿主 bind mount，没有总量/inode 配额 |
| `--ipc none` | 无 `/dev/shm` | `IpcMode=none`；JVM 走 `/tmp` 正常 |
| `--security-opt no-new-privileges:true` | setuid 提权被挡 | `SecurityOpt` 第一项；容器内无 setuid 提权路径 |
| 保留项（复核） | `--network none`、`--read-only`、`--cap-drop ALL`、`--pids-limit 64`、`--memory 256m`、`--memory-swap 256m`、`--cpus 1.0`、`--tmpfs /tmp:rw,noexec,nosuid,size=64m`、`-v <work>:/work:rw`、`-w /work`、`-e HOME=/tmp` | `docker inspect`：`NetworkMode=none`、`ReadonlyRootfs=true`、`CapDrop=[ALL]`、`PidsLimit=64`、`Memory=MemorySwap=268435456`、`NanoCpus=1000000000`、Tmpfs 一致 |

两条踩过的坑记在这里，避免后人重犯：

- **`clone` 的 8 个命名空间标志必须拆成 8 条规则**。最初写成一个 args 数组，libseccomp 对「同一参数位上的多个条件」按 OR 匹配，能拦住单个标志，但换到按 AND 解释的 runtime 会全部失效。拆开后重新构建，`clone(CLONE_NEWUSER)` 仍 EPERM，`pthread`/fork/JVM 正常。
- **`--cgroupns private` 没有加**：本机 cgroup v2 默认已 private，显式加在 cgroup v1 主机上有兼容风险。

### 7.3 加固后 16 条用例实测（run4，2026-11-18 12:28～12:30）

| 用例 | 期望 | 实测 | 端到端 | 单用例耗时 |
| :--- | :--- | :--- | :--- | :--- |
| 正常 a+b（Python） | AC | **AC** | 8.37 s | 826～914 ms |
| 正常 a+b（Java） | AC | **AC** | 7.71 s | 933～987 ms |
| 正常 a+b（JavaScript） | AC | **AC** | 5.49 s | 811～900 ms |
| `while True: pass` | TLE | **TLE** | 13.68 s | 2074～2099 ms |
| 无限往 stdout 写 | OLE | **OLE** | 5.12 s | 696～918 ms |
| 除零 | RE | **RE** | 6.47 s | 793～1979 ms |
| Java 少写分号 | CE | **CE** | 2.05 s | — |
| Java 持续分配内存 | MLE | **MLE**（`OutOfMemoryError`） | 9.75 s | 1284～1821 ms |
| 读 `C:\Windows\win.ini` | 拒绝 | **RE**，`FileNotFoundError` | 6.14 s | 775～833 ms |
| 连 `127.0.0.1:3306` | 拒绝 | **RE**，连接失败 | 6.13 s | 958～1024 ms |
| 往 `/` 写文件 | 拒绝 | **RE**，`Read-only file system` | 5.46 s | 812～892 ms |
| 往 `/tmp` 写文件 | 允许 | **WA**（输出 `ok`，不是答案） | 5.12 s | 808～830 ms |
| fork 50 个子进程 | 受限 | **TLE**（pids 打满后 sleep 超时） | 13.50 s | 2071～2088 ms |
| 起 `sleep 25` 子进程后立刻退出 | 随容器清理 | **WA**，容器销毁、无残留 | 6.15 s | 931～1056 ms |
| 打印当前目录 | 独立目录 | **WA**，`/work` | 5.28 s | 832～902 ms |
| 源码约 70 KB | 拒绝 | **400** `source exceeds 65536 bytes` | 未入队 | — |

判定分布与加固前完全一致：AC / TLE / OLE / RE / CE / MLE 各归各位；读宿主路径、连本机端口、写根目录仍然被拒。`tmp-write`、`orphan-child`、`cwd-env` 判 WA 是因为它们的输出不是题目答案，这三条验证的是能力边界，不是正确性。完整原始输出见 `E:\dsh-try\tmp\probe-run4.log`，结构化结果见 `E:\dsh-try\tmp\sandbox-probe-container-run4.json`。

### 7.4 手动验证（与后端完全相同的参数）

```
uid=65534(nobody) gid=65534(nogroup)
hi                                   # /work 可写
-rw-r--r-- 1 nobody nogroup 3 t.txt  # 属主 nobody
unshare(NEWUSER): rc=-1 errno=1 (EPERM)
mount(tmpfs):     rc=-1 errno=1 (EPERM)
ptrace(ATTACH):   rc=-1 errno=1 (EPERM)
keyctl(GET_KEYRING_ID): rc=-1 errno=1 (EPERM)
clone(NEWUSER):   rc=-1 errno=1 (EPERM)
```

### 7.5 性能复测：加固不是回退原因

**现象**：加固后单用例从 400 ms 左右涨到 800～1000 ms，ac-python 端到端 6782 ms（加固前 4270 ms）。复跑三次都稳定：

| 运行 | ac-python 端到端 | 单用例 |
| :--- | :--- | :--- |
| 加固前（安静） | 4.27 s | 397～417 ms |
| run3（12:22～12:25） | 6.78 s | 821～890 ms |
| run4（12:28～12:30） | 8.37 s | 826～914 ms |
| run5（12:32～12:34） | 7.44 s | 803～886 ms |

**但这台机器当时不安静**：游戏 `Client-Win64-Shipping`（鸣潮，12:18:41 启动）占用约 486% CPU、2.6～3.0 GB 工作集；宿主 15.2 GB 内存只剩 477～630 MB 可用；`\Memory\Pages/sec` 1.5～3.0 万（正常应 <100）；连 `docker ps` 都要 240 ms。run3 与 run4 都发生在这段时间里，因此「加固前 397 ms」和「加固后 826 ms」不是同一负载下的对比。

**对照实验**（同一负载下交替跑 5 轮，同一个 AC 程序，除被比较项外参数完全相同）：

| 变体 | `--user` | seccomp | 5 次均值 | 最小～最大 |
| :--- | :--- | :--- | :--- | :--- |
| 加固后 | 65534:65534 | 自定义拒绝清单 | 768 ms | 742～786 ms |
| root + Docker 默认 | 0:0 | 默认 profile | 804 ms | 744～871 ms |
| nobody + Docker 默认 | 65534:65534 | 默认 profile | 775 ms | 750～823 ms |
| root + 自定义清单 | 0:0 | 自定义拒绝清单 | 792 ms（含 1 次 2555 ms 离群） | 741～2555 ms |
| 只启动 `true`（加固后） | 65534:65534 | 自定义 | 714 ms | — |
| 只启动 `true`（root/默认） | 0:0 | 默认 | 758 ms | — |

**结论**：

- 加固项本身的开销 ≈ 0（768 vs 804 ms，差值在噪声内，加固后甚至略快）。seccomp 是 19 条拒绝规则、默认放行，命中成本可以忽略；`--user` 只是容器内 uid，不改变执行路径。
- 慢在 **容器启动**：`docker run true` 现在就要 710～760 ms，而加固前整条 AC 用例才 397～417 ms。翻倍发生在 Docker Desktop/WSL2 拉起容器的路径上，与宿主 CPU 争抢、内存换页（Pages/sec 上万）吻合。
- 因此 400→800 ms 是**环境回退，不是加固代价**。诚实地说：游戏一直在跑，这次没能拿到真正的「安静状态」样本；等机器空闲时按同样脚本复测，预期回落到 400～550 ms。这条待验证项已记入残余风险。

### 7.6 残余风险（最新）

1. **内核共享**：所有容器共享 Docker Desktop WSL2 内核（6.18.58.1-microsoft-standard-WSL2），内核 0day 仍可逃逸；未使用 Kata/gVisor 等强隔离。
2. **无 userns-remap**：容器以 nobody 运行，但没有独立用户命名空间映射；Docker daemon 未启用 userns-remap。
3. **镜像风险**：`judgex-runner:latest` 约 772 MB（本地镜像 ID `sha256:d407152bfc01`，2026-11-12 构建），基础镜像未固定 digest、未做 CVE 扫描/最小化，含完整 JDK/Node/Python 工具链，供应链与镜像漏洞面仍在。
4. **工作目录无配额**：`/work` 是宿主 bind mount，单文件被 fsize 限制 64 MB，但可以写多个文件/大量小文件耗尽磁盘；无 inode/总量配额；宿主上该目录 0777，其他本机进程可读写。
5. **seccomp 是拒绝清单而非白名单**：默认放行仍允许 `execve`、`fork`、`memfd_create`、`socketpair`、`chmod` 等大量系统调用；AF_UNIX socket、共享内存替代路径等未封；新内核攻击面仍开放。
6. **资源限制不完整**：无磁盘 I/O（blkio）限制、无总磁盘配额；CPU 1.0、内存 256 MB、pids 64 已限。
7. **`/proc` 可见自身进程**：PID 命名空间隔离，但容器内仍可读自身 `/proc`；ptrace/pidfd_getfd 已封，跨容器不可见。
8. **Docker daemon 暴露面**：未挂载 `docker.sock`（好），但 daemon 本身以高权限运行在宿主，属平台风险。
9. **判题工作目录生命周期**：判题结束 `deleteQuietly` 删除 `sub-<id>`；异常崩溃时可能残留目录（本轮验证未出现）。
10. **性能复测受环境限制**：本轮宿主有游戏与高换页，未取得安静状态样本；「加固后回落到 400～550 ms」待机器空闲时复测确认。

## 八、仍然存在的限制

1. **本机模式不隔离文件系统和网络**：实测能读 `C:\Windows\win.ini`、能连本机 3306。要挡这两项必须用容器模式。
2. **容器模式已非 root + seccomp，但仍有残余风险**：容器内以应用的 uid:gid 运行（Linux 上是 `996:988`；容器里的 root 在 `--cap-drop ALL` 之后没有 DAC 覆写权限，反而写不了 `/work`）、套 19 条拒绝规则的 seccomp profile，`--cap-drop ALL`、只读根、`no-new-privileges`、`--ipc none` 与 ulimit 全部生效。仍缺 userns-remap、镜像 digest 固定、`/work` 配额、blkio 限制；完整清单见 7.6。
3. **容器模式的 C++ 依赖镜像自带的 g++**：初版镜像没有 g++，`cpp` 在容器模式下编译失败；补装之后 Linux 部署机上 C++ 编译与运行都通过（见第十节）。Node 仍取决于宿主是否装了 node，因为语言清单来自宿主探测。
4. **Python / C++ 在本机模式没有内存上限**：Windows 没有 `rlimit`，本机模式只有 Java（`-Xmx`）和 Node（`--max-old-space-size`）能按题目配置压住；容器模式对四种语言都靠 `--memory` 统一限制。
5. **内存峰值一栏仍是 `—`**：两种模式都没有采集实际 RSS，MLE 靠运行时自报 OOM（本机）或退出码 137（容器）。
6. **判定耗时仍受负载影响，但已不计入判定**：同一个 a+b，容器模式下程序自身耗时约 45～85 ms（Java 含 JVM 启动约 130～160 ms），宿主忙时这段耗时会轻微上浮；容器启动（0.6～1.2 s）单独给宽限、不进时限，也不再写进 `time_ms`（见第九节）。报告里的端到端毫秒数只用于横向比较。
7. **并发数据只有 3 个用户**：演示账号只有三个，更多用户的并发与限流没有压测数据。
8. **镜像需要本地构建**：`docker/runner/Dockerfile` 依赖能访问镜像仓库的代理（本机走 `docker.m.daocloud.io`），离线机器上要先准备好基础镜像。

## 九、判定口径：时限只计程序运行时间（2026-11-18）

前八节里的 TLE 数字（2022～2099 ms）都是**旧口径**：从 `docker run` 发出开始计时，到进程退出结束。容器启动（安静时 0.6～0.8 s，宿主忙时 1 s 以上）因此被算进了单用例时限。题目时限 2000 ms 时它只是白吃掉三分之一预算；换成时限更紧、或者输出刚好触顶的用例，就会把本应 AC / OLE 的结果挤成 TLE。实测同一份 AC 程序连续提交，出现过 **4 次 OLE + 1 次 TLE**（原始日志见 `E:\dsh-try\tmp\sandbox-hardening-report.md`、`probe-run*.log`）。

### 9.1 新口径

- **容器内打点**：`DockerSandbox` 把只读的 `clock.sh` 挂到 `/judgex-clock/`，用它包住原命令——`date +%s%N > .judgex-start` → 执行用户程序 → `date +%s%N > .judgex-end`。两个文件写在 `/work`（宿主 bind mount），宿主直接读，`runMs = (end - start) / 1e6`；文件缺失（被保险丝强杀）时 `runMs = -1`。脚本用 LF 换行、临时文件不进 stdin/stdout，跑完即删。
- **判定用 `effective`**：`effective = runMs >= 0 ? runMs : 墙钟 elapsed`；`effective > limit + 100 ms`（抖动容差）才判 TLE。
- **外层超时只是保险丝**：`waitFor(limit + startupAllowance)`。docker 模式默认 `+3000 ms`、local 模式 `+500 ms`，配置项是 `judgex.sandbox.startup-allowance-ms` / `judgex.sandbox.local-startup-allowance-ms`。被保险丝掐断（`runMs = -1` 且超时）仍然判 TLE，展示耗时记为时限本身而不是「时限 + 宽限」。
- **写库用 `effective`**：`test_results.time_ms` 与 `submissions.total_time_ms` 都是程序自身耗时。
- **编译同样处理**：编译 30 s + 3000 ms 宽限，展示用编译进程自身耗时。
- 判定顺序不变：OLE 先于 TLE；CE / RE / MLE 逻辑未动。

### 9.2 16 条用例：改前 / 改后

同一台机器、同一组用例，只换 jar（改前 = `f25f8a0`，改后 = 本次提交）：

| 用例 | 改前单用例 | 改后单用例 | 判定 |
| :--- | :--- | :--- | :--- |
| 正常 a+b（Python） | 725～816 ms | **46～56 ms** | AC → AC |
| 正常 a+b（Java） | 710～840 ms | **134～158 ms** | AC → AC |
| 正常 a+b（JavaScript） | 673～706 ms | **75～83 ms** | AC → AC |
| `while True: pass` | 2067～2088 ms | **2000～2000 ms**（保险丝掐断，记为时限） | TLE → TLE |
| 无限往 stdout 写 | 638～1009 ms | **601～661 ms** | OLE → OLE |
| 除零 | 661～699 ms | **46～55 ms** | RE → RE |
| Java 少写分号 | — | — | CE → CE |
| Java 持续分配内存 | 1120～1596 ms | **373～528 ms** | MLE → MLE |
| 读 `C:\Windows\win.ini` | 658～779 ms | **49～55 ms** | RE → RE |
| 连 `127.0.0.1:3306` | 776～820 ms | **150～158 ms** | RE → RE |
| 往 `/` 写文件 | 665～791 ms | **51～53 ms** | RE → RE |
| 往 `/tmp` 写文件 | 652～695 ms | **45～53 ms** | WA → WA |
| fork 50 个子进程 | 2063～2094 ms | **3376～3499 ms**（程序真跑完，按运行时间判） | TLE → TLE |
| 起 `sleep 25` 子进程后立刻退出 | 763～813 ms | **169～179 ms** | WA → WA |
| 打印当前目录 | 633～664 ms | **45～51 ms** | WA → WA |
| 源码约 70 KB | — | — | 400 → 400 |

16 条判定与改前**逐条一致**：AC / CE / TLE / OLE / RE / MLE 各归各位，读宿主文件、连内网、写根目录仍然被拒，70 KB 源码仍然 400。Java 的 134～158 ms 比 Python 高，是因为 JVM 启动算在程序自身运行时间里（口径如此），不再额外给 2 秒。

### 9.3 写库数字（AC 用例）

| 提交 | 语言 | 改前 `total_time_ms` / 单用例 | 改后 `total_time_ms` / 单用例 |
| :--- | :--- | :--- | :--- |
| #240 → #270 | Python | 3823 / 765,725,757,760,816 | **254 / 55,56,49,46,48** |
| #241 → #271 | Java | 3923 / 840,812,791,710,770 | **750 / 150,154,154,158,134** |
| #242 → #272 | JavaScript | 3485 / 706,703,700,673,703 | **390 / 78,78,83,75,76** |

改前 `total_time_ms` 里 80% 以上是容器启动；改后剩下的就是程序自己——Python a+b 单用例不到 60 ms。

### 9.4 抗抖动验证

用 8 个 PowerShell 忙循环把 8 个核跑满（每个单线程死循环，宿主 32 线程），期间连续提交同一份 Python a+b **10 次**（脚本 `E:\dsh-try\tmp\jitter-test.ps1`，结果 `jitter-result.json`）：

```
[1/10] AC total=302ms cases=54~72ms   [6/10] AC total=280ms cases=52~62ms
[2/10] AC total=263ms cases=48~64ms   [7/10] AC total=272ms cases=51~57ms
[3/10] AC total=264ms cases=52~54ms   [8/10] AC total=277ms cases=53~58ms
[4/10] AC total=305ms cases=58~64ms   [9/10] AC total=264ms cases=50~57ms
[5/10] AC total=267ms cases=49~56ms   [10/10] AC total=299ms cases=55~63ms
AC 10/10
```

**10/10 全部 AC**，单用例 48～72 ms，没有一次 TLE/OLE。同样的负载在旧口径下足以让启动时间涨到 800～1000 ms，正好卡在 OLE/TLE 的判定边上。

### 9.5 真 TLE 仍然判 TLE

- `while True: pass`：程序永远不结束，5 秒保险丝（2000 + 3000）到点强杀，`runMs = -1`，仍判 **TLE**，展示 2000 ms（= 时限），端到端 26.4 s（5 个用例 × 5 s 保险丝，这是变慢的部分）。
- fork 50 个子进程：程序实际跑约 3.4 s 后结束，`runMs` 有值，按运行时间判 **TLE**（3.4 s > 2.1 s），不再是「启动时间背锅」。

### 9.6 代价与注意

- TLE 提交变慢：每个用例最多要等 `limit + 3000 ms` 才掐断（原来只等 2 s），`while True` 这类 5 用例从 12 s 变成 26 s。这是「保险丝必须比时限宽」的必然代价。
- 每次执行多一个只读挂载（`/judgex-clock`，脚本约 60 字节），实测对启动耗时没有可测影响。
- 时钟脚本物化失败时（`%TEMP%` 不可写）自动退回旧行为：不挂载、按墙钟判定，并在日志里打 `could not prepare the sandbox clock script`——判题不中断，只是失去精度。
- 打点用的是容器内 `CLOCK_MONOTONIC` 风格的墙上时钟（`date +%s%N`，纳秒），同一容器内两次取值的差值不受 NTP 跳变影响。

## 结论

- **判题可信**：AC / WA / TLE / OLE / RE / CE / MLE 七种判定在两种模式下都有实测样本，时限与输出上限卡在毫秒级，边界值不会误判。
- **资源可控**：超时、超输出、超内存都能被杀掉；进程树能杀干净；容器有名字，超时杀掉的是容器本身而不是壳；工作目录会清理。
- **两种模式的定位**：`local-process` 只适合本机开发与演示，它保证的是「判题结果可信」，不是「宿主机安全」；`docker` 才补齐了文件系统、网络、内存三块隔离，代价是每用例多 300～450 ms。
- **还差的口子**：真实内存峰值采集、userns-remap 与镜像 digest 固定、`/work` 配额与 blkio 限制。前一项影响观测，后两项是安全债；完整清理见 7.6。

## 十、Linux 服务器复测（2 核 2 GB）

环境：阿里云轻量 2C2G，Ubuntu 24.04，可分配内存 1613 MB；JDK 17.0.21.1、MySQL 8.0.46、Redis 7.0.15、Docker 29.1.3；判题并发 1，RocketMQ 关闭，容器以 `996:988` 运行。镜像补装了 g++（Debian 13，`g++ 14.2`）。

| 提交 | 端到端 | 逐用例 | 判定 |
| :--- | ---: | :--- | :--- |
| Java 17 A+B | 3.9 s | 5 × AC | AC 100 |
| Python 3 A+B | 2.2 s | 5 × AC | AC 100 |
| C++17 A+B | 2.2 s | 5 × AC | AC 100 |
| `while True: pass` | 26.3 s | 5 × TLE（约 5 s/例） | TLE |

隔离现场验证（提交 Python 主动试探）：

| 试探 | 结果 |
| :--- | :--- |
| 读宿主 `/opt/judgex/judgex.jar` | `FileNotFoundError` |
| 连 `127.0.0.1:3306` | `ConnectionRefusedError` |
| 写 `/etc/judgex-pwn` | `Read-only file system` |
| 列 `/` | 只看到容器自己的根（`.dockerenv`、`judgex-clock` 等） |
| 跑完 `docker ps -a` | 0 个残留 |

代价：每个用例多 1～3 秒容器启动，2 核机器上 5 个用例的 TLE 提交要 26 秒才出终态；1 核并发下这是可以接受的取舍，想要更快只能上容器池预热。
