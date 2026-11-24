# 部署上线

把 JudgeX 跑到一台公网服务器上。目标形态：

```
浏览器 ──HTTPS──► Nginx ─┬─ /      前端静态文件（frontend/dist）
                          └─ /api/  ──► 127.0.0.1:18900  jar（systemd）
                                              │
                                ┌─────────────┼──────────────┐
                             MySQL          Redis       docker run（判题容器）
```

前端和接口同源，因此不经过 CORS；后端只监听 `127.0.0.1`，外网只能从 80/443 进。

## 0. 选机器

| 方案 | 能不能用 | 说明 |
| :--- | :--- | :--- |
| 轻量云服务器 2C4G（腾讯云/阿里云） | ✅ 推荐 | 大陆机器绑域名要备案；没备案先用 `http://IP` |
| 海外 VPS（Vultr/Hetzner，$5 起） | ✅ | 免备案，国内访问偏慢但不影响判题 |
| Render / Railway / Fly.io | ❌ | 没有 Docker 守护进程，判题只能退回本机模式，等于公开一个远程执行入口 |
| 家里机器 + Cloudflare Tunnel | ⚠️ | 只能当演示，断电或 IP 变动就挂 |

2C2G 会非常紧（MySQL ~400MB + JVM 512MB + 镜像 772MB），磁盘至少 20G。

## 1. 系统依赖

```bash
sudo apt update
sudo apt install -y openjdk-17-jre mysql-server redis-server nginx docker.io git
sudo systemctl enable --now mysql redis-server docker
```

判题语言探测读的是**宿主机** PATH，而不是镜像里的工具链。缺哪个，前端语言下拉框里就没有哪个：

```bash
sudo apt install -y python3          # 必装：否则 Python 不可选
sudo apt install -y python-is-python3  # 探测找的是 python，Ubuntu 默认只有 python3
# 可选：nodejs、g++。不装的话这两门语言不可选，其余功能不受影响。
```

## 2. 数据库

```bash
sudo mysql -e "CREATE DATABASE judgex CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
sudo mysql -e "CREATE USER 'judgex'@'localhost' IDENTIFIED BY '换成强密码';"
sudo mysql -e "GRANT ALL PRIVILEGES ON judgex.* TO 'judgex'@'localhost';"
sudo mysql -e "FLUSH PRIVILEGES;"
```

表结构由 Flyway 在首次启动时自动创建，不需要手工执行 SQL。

## 3. 代码与用户

```bash
sudo useradd -r -m -s /bin/bash judgex
sudo usermod -aG docker judgex      # 判题容器需要 docker
sudo mkdir -p /opt/judgex /var/lib/judgex/work /etc/judgex
sudo chown -R judgex:judgex /opt/judgex /var/lib/judgex

sudo -u judgex git clone https://github.com/Xiangyfff/JudgeX.git /opt/judgex
```

## 4. 构建

```bash
cd /opt/judgex/backend && sudo -u judgex mvn -DskipTests package
cd /opt/judgex && sudo docker build -t judgex-runner:latest docker/runner
cd /opt/judgex/frontend && sudo -u judgex npm ci && sudo -u judgex npm run build
```

镜像只在第一次需要构建（约 772MB），之后 `deploy/deploy.sh` 发现已存在就跳过。

## 5. 配置与启动

```bash
sudo cp /opt/judgex/deploy/judgex.env.example /etc/judgex/judgex.env
sudo nano /etc/judgex/judgex.env          # 填数据库密码、JWT 密钥、初始账号密码
sudo chown root:judgex /etc/judgex/judgex.env
sudo chmod 640 /etc/judgex/judgex.env

sudo cp /opt/judgex/deploy/judgex.service /etc/systemd/system/judgex.service
sudo systemctl daemon-reload
sudo systemctl enable --now judgex
sudo journalctl -u judgex -f
```

启动日志里应当看到 `Tomcat started on port 18900`。验证：

```bash
curl -s http://127.0.0.1:18900/api/health
# {"status":"UP","sandbox":"docker","redis":"UP","languages":3,...}
```

`sandbox` 必须是 `docker`。如果是 `local`，说明 `JUDGEX_SANDBOX` 没生效，**此时任何登录用户都能在你的服务器上执行任意代码**。

## 6. Nginx

```bash
sudo cp /opt/judgex/deploy/nginx.conf /etc/nginx/sites-available/judgex.conf
sudo ln -sf /etc/nginx/sites-available/judgex.conf /etc/nginx/sites-enabled/judgex.conf
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

`nginx.conf` 里那三行（`proxy_buffering off`、`proxy_set_header Connection ''`、`proxy_read_timeout 300s`）不能删——删了页面上的判题进度会一直不动，等到连接超时才一次性刷出来。

签发证书：

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com
```

大陆服务器绑域名需要先备案（个人 2～4 周），备案前用 `http://IP` 访问即可。

## 7. 防火墙

```bash
sudo ufw default deny incoming
sudo ufw allow OpenSSH
sudo ufw allow 'Nginx Full'
sudo ufw enable
```

MySQL、Redis、18900 都不要对外开放。确认一下：

```bash
# 从你自己电脑执行，三条都应该超时或拒绝
telnet 服务器IP 3306
telnet 服务器IP 6379
telnet 服务器IP 18900
```

## 8. 上线前自查

- [ ] `/api/health` 里 `sandbox` 是 `docker`
- [ ] 提交一段死循环代码：按时判 TLE，`docker ps -a` 无残留容器
- [ ] 提交一段读 `/etc/passwd` 的代码：判 RE，读不到内容
- [ ] 默认账号密码已换成强密码（或 `JUDGEX_SEED_ENABLED=false`）
- [ ] `JUDGEX_JWT_SECRET` 不是仓库里的默认值
- [ ] 打开任意题目，提交后进度是逐用例跳动的（SSE 没被缓冲）
- [ ] `sudo reboot` 之后服务自动起来

## 9. 日常运维

```bash
sudo /opt/judgex/deploy/deploy.sh                 # 更新：拉代码、构建、重启、健康检查
sudo journalctl -u judgex -n 200 --no-pager       # 看日志
docker ps -a                                      # 有没有残留判题容器
find /var/lib/judgex/work -mtime +1 -delete       # 清理异常退出留下的工作目录
```

备份与日志轮转：

```bash
# 每天 3 点备份，保留 14 天
echo '0 3 * * * mysqldump judgex | gzip > /var/backups/judgex-$(date +\%F).sql.gz' | sudo crontab -
echo 'find /var/backups -name "judgex-*.sql.gz" -mtime +14 -delete' | sudo crontab -

# systemd 日志限额
sudo sed -i 's/^#SystemMaxUse=.*/SystemMaxUse=500M/' /etc/systemd/journald.conf
sudo systemctl restart systemd-journald
```

## 10. 这个项目特有的坑

| 坑 | 现象 | 处理 |
| :--- | :--- | :--- |
| 语言探测用宿主 PATH | 容器里明明有 python3，前端却选不到 Python | 装 `python3` + `python-is-python3` |
| 容器用户与挂载权限 | `--cap-drop ALL` 之后容器里的 root 失去 `CAP_DAC_OVERRIDE`，写不了属主是 `judgex` 的 `/work`，Java/C++ 编译报 `Permission denied`；换成 `--user nobody` 同样写不了 | `JUDGEX_SANDBOX_USER` 填**应用自己的 uid:gid**（`id judgex` 得到的 996:996），容器以非 root 身份只写自己的目录 |
| systemd `PrivateTmp` 切断了挂载源 | 容器报 `cannot open /judgex-clock/clock.sh: No such file`，所有提交变 CE | 服务私有 `/tmp` 与守护进程看到的 `/tmp` 不是同一个；给 JVM 加 `-Djava.io.tmpdir=/var/lib/judgex/tmp`，让挂载源落在共享路径上 |
| 容器内 apt 走境外源 | 构建镜像卡在 `apt-get update` 十几分钟不动 | Dockerfile 里先 `sed` 成 `mirrors.aliyun.com` 再装 g++，1 分半完成 |
| SSE 被 Nginx 缓冲 | 进度条不动，几十秒后一次性出现 | 关 `proxy_buffering`，`Connection ''` |
| 演示账号 | 公网上等于后门 | 首启用 `JUDGEX_ADMIN_PASSWORD` 注入强密码 |
| RocketMQ 很重 | 单机 1GB+ 内存 | 小机器直接 `JUDGEX_MQ_ENABLED=false`，判题不受影响，只是通知会堆积在发件箱 |
| 并发过高 | 2C 机器 CPU 打满，判题全变 TLE | `JUDGEX_WORKER_CONCURRENCY=2`，必要时 1 |

## 11. 一次真实的部署记录

跑通这套流程的机器：阿里云轻量应用服务器 **2 核 / 2 GB / 40 GB**，Ubuntu 24.04，实际可用内存 1613 MB。整套下来内存占用稳定在 900 MB 左右，留出 700 MB 余量。

### 参数

| 组件 | 设置 | 原因 |
| :--- | :--- | :--- |
| JVM | `-Xms128m -Xmx320m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m` | 堆再大就挤到 MySQL；SerialGC 在小堆上比 G1 省 CPU |
| MySQL | `innodb_buffer_pool_size=128M`、`max_connections=60`、`performance_schema=OFF` | 默认 128M 缓冲池就要 400 MB 常驻 |
| Redis | `maxmemory 64mb` | 只放限流桶、worker 存活键和排行榜 |
| 判题并发 | `JUDGEX_WORKER_CONCURRENCY=1` | 2 核跑一个容器刚好，多了 CPU 抢不到时限 |
| RocketMQ | `JUDGEX_MQ_ENABLED=false` | 单机 broker 要 1 GB+，判题完全不依赖它 |

### 实测

五个用例的 A+B，从提交到终态（含每用例一次的容器启动）：

| 语言 | 耗时 | 结果 |
| :--- | ---: | :--- |
| Java 17 | 3.9 s | AC 100 |
| Python 3 | 2.2 s | AC 100 |
| C++17 | 2.2 s | AC 100 |
| 死循环 | 26.3 s | TLE（5 个用例 ×2 s 时限 + 容器启动） |

容器隔离现场验证（提交一段 Python 去试探）：

| 试探 | 结果 |
| :--- | :--- |
| 读宿主机 `/opt/judgex/judgex.jar` | `FileNotFoundError`（宿主目录没挂进来） |
| 连 `127.0.0.1:3306` | `ConnectionRefusedError`（`--network none`） |
| 写 `/etc/judgex-pwn` | `Read-only file system`（根文件系统只读） |
| 跑完检查 `docker ps -a` | 0 个残留 |

`reboot` 之后 45～60 秒恢复 200，`systemctl is-enabled judgex nginx mysql redis-server docker` 五个都是 enabled。
