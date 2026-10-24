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
| M2 | conf 子集解析 + server/location 路由（优先级照抄 nginx：`=` → `^~` → 最长前缀 → `~` 正则） | 优先级用例表全绿 | ☑ |
| M3 | 反代核心：头部改写、流式响应泵、RR+weight、三段超时 | mock 上游转发；SSE 边收边到；256MB 慢读/128MB 堆无 OOM | ☑ |
| M4 | 弹性与复用：失败重试、被动健康检查、上游连接池 keepalive（含 `proxy_http_version`）、least_conn/ip_hash、draining 停机 | kill -9 后端无 502 风暴；重启不断流 | ☐ |
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
4. **两段 keep-alive 独立**（M3 只做前半句：后端池化推迟到 M4，见 9.6 的 M3-D5）：前端由 KeepAliveHandler 管闲置超时；后端进 UpstreamPool
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
- M2 验收（2026-10-12，JDK 1.8.0_351）：`mvn -B clean package` 通过，67 个用例全绿
  （`ConfParserTest` 18 + `RouterTest` 6 + `ConfIntegrationTest` 8 + `StaticFileHandlerTest` 18
  + `HttpServerIntegrationTest` 7 + `ServerOptionsTest` 10），比 M1 的 33 个翻倍。
  用真实 conf（`-c`，单端口两个 server + 两个 location）起服务逐项核对：
  `Host: a.example.com` → `A-INDEX`、`Host: b.example.com` → `B-INDEX`、未匹配 Host 回落第一个 server；
  `/only-a.txt` 在 a 为 200、在 b 为 404（root 确实按 server 分开）；
  `location /assets/ { root ...; }` 覆盖 root 后 200、`location = /exact.txt` 200；
  HEAD 无 body、`Range: bytes=0-3` → 206 `bytes 0-3/6` 且内容为前 4 字节、POST → 405；
  启动日志逐条打印 conf 路径、监听地址与每个 server 的名字/root/default_server 标记。
  错误路径同样核对：`-c` 与 `--port` 互斥报错、坏 conf 报
  `broken.conf:3:26: 'location' requires a '{ ... }' block`，两者均以 exit=2 退出。
  实测发现并修复：`root` 的语义是 **root + 完整 URI**，`location /assets/ { root X; }` 不会把
  location 前缀从路径里剥掉（`alias` 才那样，已推迟到 P2）—— 集成用例最初按错误理解摆文件，
  现已把这条写进 8.1 节。
- M3 验收（2026-10-24，JDK 1.8.0_351）：`mvn -B clean package` 通过，94 个用例
  （1 个可选 soak 默认跳过，单独跑通）。代理自动验收 12 个用例覆盖：hello 转发、
  请求头重写（Host / X-Forwarded-For / Connection 点名的扩展跳头清洗）、响应跳头清洗、
  SSE 首块在上游阻塞时先到、同一客户端连接两次请求对应两条上游连接、上游无响应关闭 → 502、
  读超时 → 504、2MB 二进制逐字节一致、POST body 流式转发、静态与代理共存、代理连接空闲超时。
  背压验收：256MB 响应 + 裸 socket 每 64KB 睡 1ms 慢读，在 `-Xmx128m`（小于响应体，
  若整包缓冲必然 OOM）+ `-Dio.netty.leakDetection.level=PARANOID` 下 7.9s 跑完，
  无 OOM、无 LEAK 日志。命令：
  `mvn -B test -Dtest='ProxyIntegrationTest#slowClientBackpressureSoak' -Dnginx4j.soak=true -DargLine='-Xmx128m -Dio.netty.leakDetection.level=PARANOID'`。
  测试床使用内置裸 socket mock upstream（`/hello`、`/headers`、`/hop`、`/sse`、`/big`、`/soak`、
  `/close`、`/hang`），未新增 Jetty demo；9.7/9.8 中的 Jetty + curl 清单保留为可选手工验收。
  实测发现并修复：`IdleStateHandler` 的三个参数是 reader/writer/all，读超时必须放在第一个，
  否则 `proxy_read_timeout` 会静默失效；旧上游连接迟到的关闭/可写事件通过事务归属校验隔离，
  不会误伤同一客户端上的下一笔请求。

## 8. M2 实施设计（conf 子集 + 路由）

### 8.1 支持的指令子集（表外的指令一律**报错**，不静默忽略）

| 上下文 | 指令 |
|---|---|
| main | `worker_processes <n\|auto>`、`events { }`、`http { }` |
| events | `worker_connections <n>`（解析+校验，M6 调优时才生效） |
| http | `keepalive_timeout <time> [header_timeout]`、`root`、`index`、`autoindex`、`server { }`、`upstream NAME { ... }`、`proxy_set_header`、`proxy_connect_timeout`、`proxy_read_timeout`、`proxy_send_timeout` |
| server | `listen`、`server_name`、`root`、`index`、`autoindex`、`keepalive_timeout`、`proxy_set_header`、三条 `proxy_*_timeout`、`location { }` |
| location | `root`、`index`、`autoindex`、`keepalive_timeout`、`proxy_pass <http://host[:port] \| http://upstream_name>`、`proxy_set_header`、三条 `proxy_*_timeout` |
| upstream | `server ADDR[:PORT] [weight=N]` |

> **M3 状态**：表内反代指令已全部解析并接入运行时。`upstream` 只允许写在 http，
> `proxy_pass` 只允许写在 location；上游主机在解析期完成 DNS 解析（M3-D3）。
>
> **M3 推迟项**：`proxy_http_version` 与上游连接复用（`keepalive`）一起推到 **M4**；
> M3 恒为"一请求一条上游连接、用完即关"（见 9.2 与 9.6 的 M3-D5）。

- 词法：`#` 至行尾为注释；`{` `}` `;` 是结构符；单/双引号可包裹含空白与结构符的 token；
  含 `$` 的 token 直接报错（变量是 M5/P2 的事）。错误信息必须带 `文件名:行:列`。
- 时间：后缀 `ms/s/m/h/d`，无后缀按秒。`keepalive_timeout` 第二个值（header timeout）接受但暂不使用。
- `listen`：`PORT` / `ADDR:PORT` / `[v6]:PORT`，可带 `default_server`（或 `default`）标志；
  `ssl`/`http2` 等其余参数报错并指明是 M5 范围。
- `server_name`：精确名、`*.example.com`、`mail.*`、`.example.com`（展开为精确名 + `*.` 通配）、
  `~regex`、`""`；`_` 按 catch-all 处理。
- `location`：支持 `=`、`^~`、`~`、`~*` 与无修饰前缀；**不支持** `@named`、嵌套 location、
  `alias`、`try_files`、正则捕获组变量 —— 命中即报错说明推迟到哪个里程碑。
- 相对 `root` 相对 **conf 文件所在目录**解析（CLI 路径相对 cwd，行为不变）。
- `root` 采用 nginx 语义：**磁盘路径 = root + 完整 URI**（不是 location 前缀之后的部分）；
  `location /assets/ { root /srv; }` 请求 `/assets/a.css` 命中 `/srv/assets/a.css`。`alias` 推迟到 P2。
- `-c/--conf` 与 `--port/--root/--index/--autoindex/--keep-alive-timeout` **互斥**（报错，杜绝两套真值）；
  `--worker-threads` 仍可覆盖 conf 里的 `worker_processes`。
- **M2 只支持单一监听端口**：所有 server 的 listen 端口必须相同，否则报错。跨端口/多地址绑定推迟。

### 8.2 继承与不可变性

`keepalive_timeout`、`root`、`index`、`autoindex` 沿 http → server → location 继承，
但**在解析期就折叠进各个 Block**：运行时读到的都是最终值，不存在"向上找父级"的逻辑，也不会有继承 bug。
解析产物全部不可变，天然就是 M5 平滑 reload 要原子替换的那份快照。

### 8.3 路由算法（照抄 nginx 优先级）

- server 选择：精确名 → 最长 `*.x` 通配 → 最长 `x.*` 通配 → 配置顺序第一个命中的正则 → 默认 server
  （带 `default_server` 的，没有就是该端口第一个 server）。
- location 选择：`=` 精确命中即返回；否则取最长前缀匹配并记下它是否 `^~`，是 `^~` 就直接返回；
  否则按配置顺序试正则，首个命中即返回；否则回落到那个最长前缀；一个前缀都没有就用 server 级默认值。
- Host 处理：去掉 `:port`、转小写、去尾部 `.`；缺失或匹配不上走默认 server。
- URI 用原始 path 匹配（M2 不做 `%XX` 解码与 `//` 归一化，与 nginx 的差异记在第 5 节坑清单）；
  穿越防护仍由"解析后路径必须落在 root 之内"兜底，不受影响。

### 8.4 测试表

| 层 | 文件 | 覆盖 |
|---|---|---|
| L1 | `ConfParserTest` | 完整合法 conf 解析、继承折叠、时间/引号/注释、每条错误路径（漏分号、未知指令、重复、非法正则、嵌套 location、含 `$`、多端口、listen 参数）均断言行列号 |
| L1 | `RouterTest` | server_name 五档优先级 + Host 去端口/大小写/尾点；location 五档优先级（`=` 短路、`^~` 压过正则、正则按序、最长前缀、无匹配回落 server 默认） |
| L1 | `StaticFileHandlerTest` | 原 18 个用例**不改**（4 参数构造器保留为"无 conf 的单 server"便捷路径） |
| L2 | `ConfIntegrationTest` | `--port 0` + 临时 conf 起真实服务：按 Host 分两个 server 各自 root、location 覆盖 root、`=`/正则/前缀各返回预期内容、conf 里的 keepalive 生效 |

验收：`mvn -B test` 全绿（M1 的 33 个用例 + M2 新增），L2 至少覆盖"同一端口两个 Host 分流到不同 root"。

## 9. M3 实施设计（反向代理核心）

> 一句话目标：让一个 location 把请求**流式**转给上游，再把响应**流式**泵回来 —— 全程不聚合、不跨 EventLoop、不阻塞。

### 9.1 范围与非目标

做（对应第 2 节 M3 行）：

1. `upstream` 块与 `proxy_pass` 的 conf 子集（指令表已在 8.1 节加行）；
2. 请求/响应头改写（hop-by-hop 清洗、XFF、Host、两跳 Connection 分别重写）；
3. 流式请求/响应泵（chunk 进 chunk 出）+ 双向背压；
4. RR + weight 负载均衡（per-EventLoop 游标）；
5. 三段超时（connect / read / send）；
6. Jetty demo 测试床 + 裸 socket 集成测试（SSE、慢客户端、大下载）。

**明确不做**（写明归属，杜绝范围蔓延）：

- **上游连接复用**（连接池、`proxy_http_version`、`keepalive`）→ **M4**；M3 恒为"一请求一条上游连接、用完即关"。
  3.3 节的状态归属表与第 4 节第 4 条描述的池模型不变，只是 M3 不落地它，两者与 M3 行为因此不冲突；
- 失败重试、被动健康检查、`max_fails`/`fail_timeout`/`backup`/`down`、`least_conn`/`ip_hash` → **M4**；
- TLS/SNI、`https://` 上游、gzip、limit_req、WebSocket 隧道 → **M5**；
- 带 URI 的 `proxy_pass`（前缀替换）、`proxy_redirect`、`rewrite`/`map`、`proxy_cache`、`alias` → **P2**；
- 运行时 DNS 重解析（见 M3-D3）。

### 9.2 指令语义与校验（表在 8.1 节）

`upstream NAME { server ADDR[:PORT] [weight=N]; ... }`

- 只在 `http` 上下文；`NAME` 唯一且非空；至少一个 `server`；重名或空 upstream 报错。
- `weight` 正整数、上限 10000（照抄 nginx 上限），缺省 1。
- `server` 的其余参数（`max_fails`、`fail_timeout`、`backup`、`down`、`max_conns`）报错并指向 M4；
  `least_conn`/`ip_hash`/`keepalive` 作为 upstream 内指令同样指向 M4（`plannedMilestone` 已有这三项）。

`proxy_pass URL`

- 子集：`http://host[:port]` 或 `http://upstream_name`；端口缺省 80。
- **拒绝**：`https://`（M5）、带 path/query 的 URL（M3-D2，指向 P2）、含 `$` 的 token（`checkNoVariables`）、
  server 级或 http 级的 `proxy_pass`（提示"写在 location 内"）。
- 歧义规则照抄 nginx：URL 的 host 命中已定义 upstream 名 → 按 upstream 处理，否则按主机名。

`proxy_set_header NAME VALUE`

- 可出现在 http / server / location，**按名覆盖合并**（同名覆盖，其余继承项保留）；空 `VALUE` = 删除该头。
- `VALUE` 只接受字面量：M3 不支持 `$host`/`$remote_addr`/`$proxy_add_x_forwarded_for` 等变量（P2/M5）。
- `NAME` 非法字符（空格、冒号）、`VALUE` 含换行一律报错（头注入防线）。

`proxy_http_version`：**M3 拒绝**，与上游连接复用一起推迟到 M4。M3 对上游恒发 `Connection: close`，
一请求一条连接、响应结束即关闭 —— 复用所需的池语义只有和它一起做才自洽（M3-D5）。

`proxy_connect_timeout` / `proxy_read_timeout` / `proxy_send_timeout <time>`：复用 `parseTimeMillis`
（后缀 `ms/s/m/h/d`，无后缀按秒），三者缺省均 **60s**（nginx 缺省）。

### 9.3 配置模型改动

| 文件 | 改动 |
|---|---|
| `conf/UpstreamBlock`（新） | `name()` + 不可变 `List<UpstreamServer>` |
| `conf/UpstreamServer`（新） | host / port / weight；惰性解析出的 IP 列表（M3-D3） |
| `conf/ProxyTarget`（新，**放 conf 包**） | scheme（M3 恒 http）/ host / port / upstreamName（可空）+ `describe()`。放 conf 是依赖方向决定的：`conf ← router ← proxy`，放 proxy 会让 conf 反向依赖 |
| `conf/HttpBlock` | 增 `List<UpstreamBlock> upstreams` 与 `upstream(String)`；`singleServer(...)` 传空表，M0/M1 便捷路径不变 |
| `conf/LocationBlock` | 增 `proxyTarget`（null = 静态）、`proxySetHeaders`（保序）、三个超时 |
| `conf/ServerBlock` | 增同一组可继承字段（location 未覆盖时取它），维持"解析期折叠、运行时不查父级"（8.2 不变量） |
| `router/ResolvedLocation` | 增 `isProxy()`、`proxyTarget()`、`proxySetHeaders()`、三个超时 |
| `conf/ConfParser` | `Overrides` 增字段与 `seen` 标志，`applyInheritable` 增分支，`buildHttpBlock` 折叠顺序 http → server → location 不变；`parseHttp` 增 `upstream` 块分支；`plannedMilestone` 按 M3-D6 调整 |

**构造器连锁成本**：`LocationBlock`/`ServerBlock`/`HttpBlock` 的构造器都是 public，`RouterTest`/`ConfParserTest`/
`StaticFileHandlerTest` 直接 `new` 它们。M3 用**新增重载 + 旧签名委托新签名**的方式消化，旧签名补 null/空值
即静态行为，避免测试大面积改签名（26 个用例的构造调用不动）。

### 9.4 运行时结构

依赖方向不变：`conf ← router ← proxy ← server`。后端 pipeline 只有 `HttpClientCodec → ProxyBackendHandler`
（按需再加 `IdleStateHandler`/`WriteTimeoutHandler`）；**仍然禁用 `HttpObjectAggregator`**。
因为没有连接池，上游连接与事务同寿命：建连 → 转发请求 → 泵回响应 → 关闭。

| 类（包） | 职责 |
|---|---|
| `server/RequestDispatcher`（新） | 每 channel 一个，唯一入口：路由 → 按 `ResolvedLocation.isProxy()` 走静态或代理；统一错误响应 |
| `server/IdleTimeoutHandler`（新） | 把 M1 的 keep-alive 计时器抽出来（`start/cancel/channelInactive` 清理），静态与代理共用（M3-D1/M3-D7） |
| `proxy/ProxyFrontendHandler`（新） | 前端入站：改写请求头、建上游连接、把 `HttpRequest + HttpContent` 序列流式转发、超时、异常→502/504 |
| `proxy/ProxyBackendHandler`（新） | 后端入站：`HttpResponse + HttpContent` 流式泵回前端、清 hop-by-hop、收尾时关闭上游连接并清 TXN |
| `proxy/LoadBalancer`（新） | 平滑加权轮询：`current += weight; 选最大; current[selected] -= total`；游标 per-EventLoop |
| `proxy/ProxyTransaction`（新） | 一次代理的事务对象；两端 Channel 用 `TXN` 属性互指（3.4 的属性互指部分；"池内 TXN 必须为空"那条不变量随 M3 推迟而暂不适用）；持有超时 future 与状态机 |
| `proxy/ProxyRequestRewriter` / `proxy/ProxyResponseRewriter`（新） | 9.5 的改写规则，纯函数式、可 EmbeddedChannel 单测 |
| `http/HttpConstants` | 已备好 `HOP_BY_HOP_HEADERS`；M3 补"客户端 `Connection` 头点名的扩展跳头"处理（见 9.9 第 1 条） |

### 9.5 关键流程

**建连**：需要转发时 `new Bootstrap().group(ctx.channel().eventLoop())` 建一条新连接（硬约束：前后端同 EventLoop），
随即 `attr(TXN).set(txn)` 把两端绑成一次事务。M3 **不借也不还**：连接与这一个请求同寿命，响应结束即关。

**请求改写规则**：

- 删 hop-by-hop：固定 8 个头 **加上** 客户端 `Connection` 头里点名的 token（RFC 7230 6.1）；
- `Host`：默认改写为 `proxy_pass` 的主机（非默认端口时带端口），`proxy_set_header Host` 可覆盖；
- `X-Forwarded-For`：追加 `ctx.channel().remoteAddress()` 的 IP（取不到写 `unknown`），保留既有链；可被 `proxy_set_header` 覆盖；
- `X-Real-IP`/`X-Forwarded-Proto`：M3 **不自动加**（nginx 也不自动加，靠用户 `proxy_set_header`；无变量支持，只能写字面量）；
- `Connection`：M3 恒发 `close`（没有池就不复用上游连接；客户端那份 `Connection` 已按 hop-by-hop 删掉）；
- `Transfer-Encoding`/`Content-Length`：不手改，交给 `HttpClientCodec` 按 `HttpRequest` 编码；
- `Accept-Encoding`：原样透传（M3 没有 gzip，上游压缩会原样穿过）；
- `Expect: 100-continue`：M3 直接透传，不引入 `HttpServerExpectContinueHandler`（M5 再评估）；
- 方法：**不限 GET/HEAD**（静态路径的 405 限制不适用于代理）。

**响应改写规则**：

- 删 hop-by-hop（含上游 `Connection` 点名项）；不管上游怎么应答，本响应结束都关闭上游连接；
- `Server`/`Date`/`Content-Type`/`Content-Length`/`ETag`/`Location` 原样透传（M3 无 `proxy_hide_header`/`proxy_redirect`）；
- HEAD/304/204 只写头，`LastHttpContent` 照发；
- 上游的 `Connection` 头**绝不**透传给客户端，客户端的 Connection 语义只由 `HttpServerKeepAliveHandler` + `RequestDispatcher` 决定。

**流式泵与收尾**：

- 请求侧：`HttpRequest` 头转发后，后续 `HttpContent` 逐块转发，**禁止聚合**；
- 响应侧：`HttpResponse` 立即写出，`HttpContent` 逐块 `writeAndFlush`；
- 收尾时机：前端 `LastHttpContent` 写**完成之后**（挂 `ChannelFutureListener`，不是调用栈内）
  → `attr(TXN).set(null)` + 关闭上游连接；
- 异常/双端关闭路径：清 TXN + 关闭双端，不留任何半开连接。

**背压（必做）**：

- 后端 `isWritable()==false` → 前端 `setAutoRead(false)`；恢复可写 → 恢复读；
- 前端不可写 → 同样关后端 autoRead；
- 两侧都监听 `ChannelWritabilityChangedEvent`；不可写时绝不允许继续读入（否则 body 全堆在堆里，直接违反"大下载期间堆平稳"）。

**三段超时**：

| 指令 | 语义 | 实现 | 超时结果 |
|---|---|---|---|
| `proxy_connect_timeout` | 建连完成 | connect future + `ctx.executor().schedule` | 502（拒连）/ 504（超时）；响应头未发则可 keep-alive |
| `proxy_read_timeout` | 两次**后端入站读**之间 | 后端 `IdleStateHandler(0, read, 0)`（连接只服务于在途请求，不存在"池中空闲"状态） | 504；已发响应头则只能关前端连接 |
| `proxy_send_timeout` | 向后端单次写完成 | `WriteTimeoutHandler(send)`（与 nginx"两次写之间"是**近似**，见 9.9 第 7 条） | 同上 |

### 9.6 M3 决策记录

| # | 决策 | 结果与理由 |
|---|---|---|
| M3-D1 | keep-alive 计时器归属 | 抽成 `IdleTimeoutHandler` 单点持有；静态与代理必须共用同一个时钟，否则两条路径的计时器互不可见（会放大 5 节第 9 条的坑） |
| M3-D2 | 带 URI 的 `proxy_pass` | M3 拒绝并指向 P2；前缀替换配上变量才是完整语义，半实现最容易产出静默错路径 |
| M3-D3 | 上游主机名解析 | **解析期**用 `InetAddress.getAllByName` 解析成 IP 列表并缓存，运行期零 DNS（与 nginx 启动期解析一致；字面 IP 不走 DNS，测试全用 IP）。解析失败报 `文件:行:列`。**已定稿**，不做运行时 DNS 轮询 |
| M3-D4 | LB 游标 | per-EventLoop（沿用 3.3/3.5 P2）；M4 若发现局部视图不够准再评估全局计数 |
| M3-D5 | 上游连接复用 | **M3 不做，推迟到 M4**：连接池、`proxy_http_version`、`keepalive` 三者必须一起做才自洽。M3 一请求一条连接、响应结束即关，因此池容量这类参数在 M3 不存在 |
| M3-D6 | `proxy_next_upstream` | 从 `plannedMilestone` 的 "M3 reverse proxy" **改到 M4**（重试属 M4 弹性范围），M3 仍拒绝；`ConfParserTest` 的提示断言同步改 |
| M3-D7 | 静态路径重构幅度 | 保留 `StaticFileHandler` 的响应逻辑与 4 参数测试构造器，只把"路由"与"计时器"两个职责上移到 `RequestDispatcher`/`IdleTimeoutHandler` |

**状态：M3-D1…D7 已于 2026-10-13 定稿（M3-D5 复审后改为"推迟到 M4"），第 9 节即实现依据；
M3 已于 2026-10-24 按本节完成并通过验收（见第 7 节）。** 代码不得偏离本表；
确实需要偏离时，先改这一行再改代码（与 8.1 节的指令表同一套纪律）。8.1 表继续保留带 `（**M3**）` 的行作为唯一定义处，
不另开第二张增量表。

### 9.7 测试表

| 层 | 文件 | 覆盖 |
|---|---|---|
| L1 | `proxy/ProxyRequestRewriterTest` | hop-by-hop 全删（含 `Connection` 点名头）、XFF 追加与覆盖、Host 重写、`proxy_set_header X ""` 删头、对上游恒发 `Connection: close` |
| L1 | `proxy/ProxyResponseRewriterTest` | 响应 hop-by-hop 删净、HEAD/204/304 无 body、上游 `Connection: keep-alive` 不被采纳 |
| L1 | `proxy/RoundRobinLoadBalancerTest` | weight 1/2/3 的平滑序列（固定 N 次的期望序列）、单节点退化、per-loop 独立 |
| L1 | `conf/ConfParserTest`（增） | upstream 与 weight、引用未定义 upstream、`https://`、带 URI 的 `proxy_pass`、server 级 `proxy_pass`、upstream 内 M4 指令、`proxy_http_version` 与 `keepalive` 报错并指向 M4、`proxy_set_header` 三级继承与同名覆盖 —— 错误路径一律断言 文件:行:列 |
| L2 | `proxy/ProxyIntegrationTest` | 内置 Netty mock 上游：SSE 边收边到（裸 socket 读时间戳）、1GB 下载堆平稳 + PARANOID 无泄漏、慢读客户端背压（断言服务端真的关了 autoRead）、**一请求一连接**（上游记录连接数 = 请求数，证明没有偷偷复用）、502（拒连）、504（不响应） |
| L3 | `testbed/upstream`（Jetty demo）+ 手测清单 | 见 9.8 |

约定沿用第 6 节：集成测试一律 `--port 0` 并在 `@After` 里 `stop()`；二进制内容 `assertArrayEquals`；
带引号的头必须用裸 socket 发。

### 9.8 验收清单（对应 M3 行的三条标准）

1. `mvn -B clean package` 全绿（M2 的 67 个用例 + M3 新增），PARANOID 泄漏检测零报告。
2. 测试床补 Jetty demo（`testbed/upstream/`，本地不提交，照 `testbed/` 的排除方式）：`/hello`、`/sse`（每秒一条）、
   `/big`（大流）；conf 里 `location /api/ { proxy_pass http://127.0.0.1:9000; }`。
3. curl 清单（逐项记录输出，照 M1/M2 的方式回填第 7 节）：
   - `curl -i localhost:8080/api/hello` → 200 且 body 与直连上游逐字节一致；
   - `curl -N localhost:8080/api/sse` → 事件**边到边打**（时间戳间隔 ≈1s，而不是收完一次性输出）；
   - `curl -i -H 'Connection: close' ...` → 响应头里没有上游 hop-by-hop 残留；
   - 连续两次 `curl -i localhost:8080/api/hello` → 上游侧连接数 **+2**（M3 不复用连接，这条把"没做池"钉成预期行为）；
   - 关掉 Jetty → 502；Jetty 挂起不响应（配 `proxy_read_timeout 2s`）→ 504；
   - 大文件经代理下载后 `sha256sum` 与直连一致（流式不损坏）。
4. **背压与堆平稳**：`--worker-threads 1` + 裸 socket 慢读客户端（每 100ms 读 64KB）拉 1GB，
   在 `-Xmx256m` + `-Dio.netty.leakDetection.level=PARANOID` 下不 OOM、日志无 `LEAK`。
5. 结论按 M0/M1/M2 的样式写进第 7 节，并把 Agent.md 的"当前状态/下一步"推进到 M4。

### 9.9 预期坑（实现时验证，确认后回填第 5 节）

1. **`Connection: X-Foo` 点名的自定义跳头必须一并删** —— 只删固定 8 个是安全洞（RFC 7230 6.1）。
2. 上游连接是"一请求一条、用完即关"：收尾时漏掉 `close()` 会把 fd 一点点泄漏，也让 M4 上池化时多出一批来路不明的连接。
3. 上游应答 `Connection: keep-alive` 也必须无视并关闭 —— M3 不维护池，留着这条连接只会变成无人管理的半开连接。
4. 小响应同步写完（5 节第 9 条同源）：关闭上游连接与清 TXN 必须挂在 `writeAndFlush` 的 listener 上，不能在调用栈内做。
5. 前后端 autoRead 互关的死锁：一次事件里只改**对侧**的 autoRead，避免自己关了自己后无人恢复。
6. `LastHttpContent` 是清 TXN 的唯一合法时机；`exceptionCaught`/`channelInactive` 必须走"丢弃 + 关闭双端"，不能只关一侧。
7. `WriteTimeoutHandler` 与 nginx `proxy_send_timeout` 语义只是近似（单次写完成 vs 两次写之间），差异记档。
8. HEAD 代理：上游会给 `Content-Length` 但不发 body，前端不能因长度不符而卡住或补 body。

### 9.10 分步提交

1. `M3: parse upstream and proxy_pass directives`（conf 模型 + 8.1 表 + ConfParserTest）
2. `M3: rewrite proxy request and response headers`（两个 Rewriter + 单测）
3. `M3: pick an upstream and stream one exchange over one EventLoop`（LB + Frontend/Backend handler + 最小竖切集成测试）
4. `M3: enforce the three proxy timeouts`
5. `M3: add backpressure and the Jetty testbed`（含 `testbed/upstream/`）
6. `M3: record the M3 acceptance and move the status to M4`（回填第 7 节 + Agent.md）