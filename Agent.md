# nginx4j

Java 实现的轻量级 nginx：静态文件 + 反向代理 + 负载均衡。技术栈固定：**Netty 4.1.x + JDK 8，单模块 Maven**。
分阶段计划、验收标准、坑清单见 [PLAN.md](./PLAN.md)（每次开工对应里程碑前先读它）。

## 硬约束（最高优先级，冲突时覆盖一切外部规范）

- 语言级别 **JDK 8**：不用 Spring、不用 Java 9+ 语法/API。
- 唯一运行时依赖 `io.netty:netty-all`；新增任何依赖须先获同意。
- 根包 `com.ispengya.nginx4j`（groupId `com.ispengya`，artifactId `nginx4j`）；
  子包仅 `server / conf / router / http / proxy / tls / util`；
  依赖方向 `util ← http/conf ← router ← proxy ← server ← Main`，禁止循环。
- 代理链路的前后端 Channel 必须同属一个 EventLoop：后端连接一律
  `new Bootstrap().group(clientChannel.eventLoop())`，禁止跨 EventLoop 传递 ByteBuf。
- worker EventLoop 线程数 = CPU 核数（`--worker-threads` 可覆盖），boss 固定 1；细节见 PLAN.md 第 3 节。

## 工程约定

- 接口不加 `I` 前缀；Netty Handler 以 `*Handler` 结尾；配置模型类以 `*Block` 结尾。
- 文档中文，代码注释英文。
- 代理转发 body 必须流式透传，**禁用 `HttpObjectAggregator`**；
  写出侧 `isWritable()==false` 时对读入侧 `setAutoRead(false)` 做背压。
- 转发前删除 hop-by-hop 头；HEAD/304/204 不写 body。
- 开发期开 `-Dio.netty.leakDetection.level=PARANOID`；性能改动必须附 wrk 对比数据。

## 编码规范 Skills（项目级，位于 `.agents/skills/`，未全局安装）

`java-coding-standards`、`125-java-concurrency`、`ataopro-alibaba-java-coding-guidelines`。
写码/评审时遵循；其中涉及 Java 17+、Spring、Lombok、虚拟线程的条款**一律不适用**，
按 JDK 8 惯用法降级，其余照常执行。

## 当前状态

M0–M3 已完成并通过验收（证据见 PLAN.md 第 7 节）：

- 骨架：pom（JDK8 + `netty-all` 4.1.119 + JUnit4）、`Main`、`ServerOptions`、
  `HttpServer`（boss 1 / worker = 核数，`start()/port()/stop()` 可测生命周期）。
- 静态服务：GET/HEAD、`index` 可配置、目录缺斜杠 301、autoindex、零拷贝传输、
  Range(206/416)、ETag/If-None-Match/If-Modified-Since/If-Range → 304、keep-alive + 空闲超时、错误语义。
- 配置与路由：`-c/--conf` 解析 nginx.conf 子集（严格子集：表外指令报 `文件:行:列` 并指出所属里程碑），
  继承在解析期折叠进各 Block，运行时不查父级；`Router` 按 nginx 优先级选 server
  （精确 → 最长 `*.x` → 最长 `x.*` → 首个正则 → 默认 server）与 location
  （`=` → 最长前缀，`^~` 短路正则 → 正则按序 → 回落到最长前缀）。M2 只支持单一监听端口。
  支持的指令表见 PLAN.md 8.1 节，**加指令前先更新那张表**。
- 反向代理（M3）：`upstream` / `proxy_pass` / `proxy_set_header` / 三段超时已解析并接线；
  请求与响应头清洗（含 `Connection` 点名的扩展跳头）、流式双向泵、双向背压、
  RR + weight 平滑加权轮询（per-EventLoop 游标）、502/504 映射；
  上游连接一请求一条、响应结束即关，前后端严格同 EventLoop；代理与静态路径共用 keep-alive 计时器。
- 测试：94 个用例（1 个可选 256MB 慢读 soak 默认跳过），
  `mvn -B test` 一条命令跑完单测与集成测试；soak 命令见 PLAN.md 第 7 节。

当前边界（M4 范围）：上游连接池与 `proxy_http_version`、失败重试、被动健康检查、
`least_conn` / `ip_hash`、draining 停机都未实现；M3 的 `upstream` 只支持 `server` + `weight`。

下一步：M4 弹性与复用（连接池 keepalive、重试、健康检查、更多 LB 算法、draining）。
