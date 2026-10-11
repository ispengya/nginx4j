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

M0、M1 已完成并通过验收（证据见 PLAN.md 第 7 节）：

- 骨架：pom（JDK8 + `netty-all` 4.1.119 + JUnit4）、`Main`、`ServerOptions`、
  `HttpServer`（boss 1 / worker = 核数，`start()/port()/stop()` 可测生命周期）。
- 静态服务：GET/HEAD、`index` 可配置、目录缺斜杠 301、autoindex、零拷贝传输、
  Range(206/416)、ETag/If-None-Match/If-Modified-Since/If-Range → 304、keep-alive + 空闲超时、错误语义。
- 测试：33 个用例全绿（`StaticFileHandlerTest` 18 + `HttpServerIntegrationTest` 7 + `ServerOptionsTest` 8），
  `mvn -B test` 一条命令跑完单测与集成测试。

下一步：M3 反代最小竖切（同 EventLoop + 流式泵 + 背压），或按 PLAN.md 顺序先做 M2 配置解析。
