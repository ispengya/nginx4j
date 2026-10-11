# nginx4j 计划与细节

> 本文件存放里程碑计划、线程模型、架构决策、坑清单等实施细节。Agent.md 只留硬约束，干活前读这里。

## 1. 功能范围

| 优先级 | 范围 |
|---|---|
| P0 必做 | HTTP/1.1 解析、keep-alive、server/location 路由、静态文件（Range/ETag/If-*）、流式反向代理、upstream 轮询+weight、头部改写（XFF、去 hop-by-hop）、三段超时、访问日志 |
| P1 增强 | TLS/SNI、least_conn/ip_hash、被动健康检查（max_fails/fail_timeout）、上游连接池 keepalive、limit_req、gzip、平滑 reload（draining） |
| P2 选做 | WebSocket 隧道（101→字节泵）、rewrite/map、proxy_cache、HTTP/2、stream L4 代理 |
| 不做 | fastcgi/uwsgi/scgi、mail、SSI、共享内存 slab、accept_mutex |

## 2. 里程碑（累计约 15~20 工作日）

| 阶段 | 内容 | 验收标准 | 状态 |
|---|---|---|---|
| M0 | pom + Main + 线程模型接线 + 最小静态文件服务 | `curl :8080` 返回文件且长度/哈希一致 | ☑ |
| M1 | 静态文件补全：Range(206/416)、ETag/If-None-Match/If-Modified-Since/If-Range、目录 301、autoindex、index 可配置 | `mvn test` 33 个用例全绿；curl 逐个头/字节核对 | ☑ |
| M2 | conf 子集解析 + server/location 路由（优先级照抄 nginx：`=` → `^~` → 最长前缀 → `~` 正则） | 优先级用例表全绿 | ☐ |
| M3 | 反代核心：UpstreamPool、头部改写、流式响应泵、RR+weight、三段超时 | 代理到 Jetty demo；SSE 边收边到；大下载期间堆平稳 | ☐ |
| M4 | 弹性：失败重试、被动健康检查、least_conn/ip_hash、draining 停机 | kill -9 后端无 502 风暴；重启不断流 | ☐ |
| M5 | 增强：TLS/SNI、gzip、limit_req、access_log、WebSocket 隧道 | wss 聊天穿透代理 | ☐ |
| M6 | 压测调优：泄漏检测跑全场景、水位/池参数、与 nginx 的 wrk 对比报告 | 报告存档 | ☐ |

## 3. 线程模型（已冻结，M3 起严格遵守）

### 3.1 拓扑

| 角色 | 数量 | 对应 nginx |
|---|---|---|
| boss EventLoopGroup | **1 线程**，只 accept | master 持有监听 fd 的角色 |
| worker EventLoopGroup | **CPU 核数**（`--worker-threads` 可覆盖） | worker 进程 × 核数 |
| 单个 EventLoop | 1 线程 + 1 个 selector | 一个 worker 的单线程 epoll 循环 |
| transport | 纯 NIO | epoll（JDK8 下不用 native transport） |
| 定时器 | EventLoop taskQueue / 时间轮 | 连接上的 keep-alive、proxy_*_timeout |

**为什么 worker = 核数而不是 Netty 默认的 2×核**：对齐 `worker_processes auto`；解析与拷贝是 CPU 密集，
线程数超过核数只增加切换开销。保留 `--worker-threads` 供 M6 压测对比。

### 3.2 三条铁律

1. **EventLoop 亲和**：代理链路的前端 Channel 与后端 Channel 必须属于同一个 EventLoop。
   后端连接一律 `new Bootstrap().group(clientChannel.eventLoop())`。
   禁止跨 EventLoop 传递 ByteBuf、禁止跨 EventLoop 写对方 Channel。
   （唯一允许的跨线程点：连接出生时 boss → worker 的一次注册，属稳态外开销。）
2. **EventLoop 内禁止阻塞**：DNS 用异步 resolver；文件 IO 只走 sendfile/Range 这类内核路径；
   任何可能慢的操作丢专用小线程池。
3. **共享状态白名单**：只有 3.3 表中列出的状态可跨 loop 共享，其余一律 loop 私有。

### 3.3 状态归属

| 状态 | 归属 | 实现 |
|---|---|---|
| 连接（前端 / 后端） | EventLoop 私有，终身不迁移 | Channel 注册即绑定，天然满足 |
| 上游连接池 | **per-EventLoop** | 每 loop 一套空闲队列，无锁 |
| 负载均衡游标（RR / weight） | **per-EventLoop** | 各 loop 独立轮询，行为同 nginx 各 worker 独立 |
| 后端健康标记 | 全局共享 | 每 server 一个 `volatile` / `AtomicReference` 状态位 |
| limit_req 计数（M5） | 全局共享 | CAS 环形桶 |
| 配置树 | 全局共享 | 不可变对象 + `AtomicReference` 原子换 |

### 3.4 连接对应关系（= nginx 的 `c->data = r`）

一次代理 = 一个 `ProxyTransaction` 对象，两端 Channel 用属性互指：

```java
static final AttributeKey<ProxyTransaction> TXN = AttributeKey.valueOf("nginx4j.txn");
upstreamChannel.attr(TXN).set(txn);      // 借出时写入（等价 c->data = r）
ProxyTransaction txn = ctx.channel().attr(TXN).get();   // 后端 handler 找回事务
upstreamChannel.attr(TXN).set(null);     // 归还池前清空
```

**不变量**：池内连接的 TXN 必须为空；TXN 非空期间该连接独占一个事务；
事务彻底终结（响应完成或双端关闭）后才能归还，否则池中出现指向已死事务的野指针。

### 3.5 已拍板项

| # | 决策 | 结果 |
|---|---|---|
| P1 | 上游连接池归属 | per-EventLoop（无锁，语义同 nginx per-worker keepalive） |
| P2 | 负载均衡游标 | per-EventLoop（M4 做 least_conn 时若局部视图不够准，再评估全局计数） |
| P3 | worker 线程数 | CPU 核数（对齐 `worker_processes auto`） |

## 4. 其他架构决策

1. **两条 Pipeline 一座桥**：前端 `HttpServerCodec → HttpServerKeepAliveHandler → 路由/处理`；
   后端 `HttpClientCodec → 响应泵回前端`；ProxyBridge 是难点所在。
2. **流式优先**：body chunk 进 chunk 出，不聚合；按 `HttpRequest + HttpContent` 序列处理。
3. **背压必做**：写出不可写时关读入 autoRead，`ChannelWritabilityChangedEvent` 恢复。
4. **两段 keep-alive 独立**：前端由 KeepAliveHandler 管闲置超时；后端进 UpstreamPool
   空闲队列复用+探活；一条后端连接同一时刻只挂一个在途请求。
5. **配置不可变**：解析产物只读；reload = 原子换引用，运行中请求继续用旧快照。

## 5. Netty / JDK8 坑清单

1. ByteBuf `retain()/release()` 配对，异常分支最易漏；全程开 PARANOID 泄漏检测。
2. 禁用 `HttpObjectAggregator`（聚合大 body 必 OOM）。
3. HEAD/304/204 无 body；`Connection` 头按两跳分别重写；`Expect: 100-continue` 用
   `HttpServerExpectContinueHandler`。
4. hop-by-hop 头转发前必删：`Connection`、`Keep-Alive`、`Proxy-*`、`TE`、`Trailer`、
   `Transfer-Encoding`、`Upgrade`（仅 WebSocket 隧道除外）。
5. 池中空闲连接可能已被后端半关闭：发送失败 → 静默换一条重试（M4 重试逻辑兜底）。
6. `DefaultFileRegion`(sendfile) 与 TLS 互斥：启用 TLS 后静态文件降级为 `ChunkedFile` 流式写出。
7. 不用 epoll native transport / io_uring，纯 NIO；`sun.misc.Signal` 不作 reload 主路径。
8. **跨 EventLoop 写 Channel 是禁忌**：不会报错，只会静默引入乱序与锁开销；代理的所有读写
   都必须在事务所属 EventLoop 内完成。
9. **小响应是同步写完的**：`writeAndFlush` 的 listener 会在调用栈内立刻执行。因此"响应写完后
   启动 keep-alive 计时器"之后，解码器可能紧接着抛出同一请求尾部的 `LastHttpContent`；
   若在"任何入站消息"上取消计时器，空闲连接将永不超时（M1 踩到，已在 `StaticFileHandler`
   收敛为"只在新 `HttpRequest` 上取消"）。
10. `DefaultFileRegion.open()` 只存在于实现类，`FileRegion` 接口没有该方法；`transferTo`
    内部会自行 open，测试里不要显式调用（M1 编译错误）。

## 6. 测试策略与常用命令

| 层 | 手段 | 覆盖什么 | 何时跑 |
|---|---|---|---|
| L1 单元 | JUnit4 + Netty `EmbeddedChannel`（无 socket、毫秒级） | Handler 纯逻辑：Range 解析、条件请求、301/403、autoindex 转义、头改写 | 写代码时，`mvn test` |
| L2 集成 | JUnit4 + 裸 Socket，`--port 0` 起真实服务 | 真实 HTTP 帧、keep-alive 生命周期与空闲超时、并发、零拷贝字节一致 | 每次 `mvn test` |
| L3 冒烟 | curl 清单 / 裸 socket 探针 | 人工可读的验收证据，必要时与 nginx 逐头对比 | 里程碑末尾 |
| L4 压测 | wrk / hey / bombardier 对比 nginx | 吞吐、延迟、堆稳定性 | M3 起做基线，M6 出报告 |

约定：
- 集成测试一律 `--port 0`（拿随机端口）并在 `@After` 里 `stop()`，避免端口冲突。
- 二进制内容用 `assertArrayEquals` 比对，禁止只断言长度。
- 慢客户端/背压类测试（M3）必须用裸 Socket 控制读速，curl 做不到。
- 验证带引号的请求头（ETag/If-Range）要用裸 socket：PowerShell 传原生参数会吞掉双引号，
  curl 探针会给出假阴性（M1 踩到）。

```powershell
$env:JAVA_HOME='D:\Java\jdk8'          # 本项目按 JDK8 编译与验证
mvn -B clean package                   # 含全部单测 + 集成测试
mvn -B test                            # 只跑测试，改代码时的快循环
& 'D:\Java\jdk8\bin\java.exe' -jar target/nginx4j-0.1.0-SNAPSHOT.jar --port 8080 --root webroot `
    --index index.html --autoindex off --keep-alive-timeout 65

curl.exe -i http://localhost:8080/                                    # 根路径 -> index.html
curl.exe -i -H "Range: bytes=0-10" http://localhost:8080/index.html   # 206 + Content-Range
curl.exe -i -H "Range: bytes=9999-" http://localhost:8080/index.html  # 416 + bytes */<size>
curl.exe -sS -D - -o NUL http://localhost:8080/index.html             # 取 ETag/Last-Modified
# ETag 条件请求必须用裸 socket 发（引号不能被 PowerShell 吞掉）
```

## 7. 决策记录与 Agent 行为约定

- D1 技术栈：Netty 4.1 + JDK8，单模块（用户指定，否决虚拟线程方案）
- D2 包根：`com.ispengya.nginx4j`，groupId `com.ispengya`（用户指定，与 GitHub 账号对齐）
- D3 文档中文、代码注释英文
- D4 线程模型冻结：boss 1 + worker 核数、前后端 Channel 同 EventLoop、上游池与 LB 游标 per-loop、
  TXN 属性租借模型（见第 3 节）
- M0 验收（2026-10-11，JDK 1.8.0_351）：`mvn -B clean package` 通过（7 个单测全绿）；
  启动日志确认 `boss=1 / worker=16 EventLoop（=availableProcessors）`；
  curl 验证：GET 200 头齐全、HEAD 无 body、keep-alive 第二请求 `num_connects=0`、
  穿越防护与缺文件均 404、POST 405；下载文件 SHA256 与源文件一致（零拷贝链路正确）。
- 已知待优化：`netty-all` 会把 35 个 module 全部打进 fat jar（M6 改为按需依赖）。
- M1 验收（2026-10-11，JDK 1.8.0_351）：`mvn -B test` 33 个用例全绿（`StaticFileHandlerTest`
  18 个 EmbeddedChannel 用例、`HttpServerIntegrationTest` 7 个裸 socket 用例、`ServerOptionsTest`
  8 个）。curl/裸 socket 逐项核对：`Range: bytes=0-99` → 206 且 100 字节内容与源一致、
  `bytes=-4` 后缀形式命中最后 4 字节、`bytes=9999-` → 416 `bytes */256`、
  `bytes=0-1,4-5` 多段被忽略返回整份 200、`If-None-Match`（带引号）→ 304 且无 Content-Length、
  `If-Modified-Since`（秒级比较）→ 304、`If-Range` 命中 → 206 / 失效 → 整份 200、
  目录缺斜杠 → 301 保留查询串、`index` 生效、autoindex 列表含父目录链接且条目名正确转义/编码、
  keep-alive 复用 `num_connects=1`、空闲 2s 被服务端关闭。
  实测发现并修复：请求尾部 `LastHttpContent` 会误清 keep-alive 计时器（见第 5 节第 9 条）。
- 约定：每个里程碑小步提交，提交信息如 `M3: implement streaming proxy pump`；
  新需求先更新本文件再实现；机械性规则交给编译器/工具，不靠自觉。