# nginx4j

Java + Netty 实现的轻量级 nginx：**静态文件服务 + 反向代理 + 加权负载均衡**。

目标不是完整复刻 nginx，而是用 Java 把最常用的一条链路做扎实：静态文件、配置路由、流式反向代理，以及一套严格的 `nginx.conf` 子集解析。

## 能力

- **静态文件**：GET/HEAD、`index`、`autoindex`、目录 301、Range（206/416）、ETag / Last-Modified / If-* 条件请求、零拷贝发送、keep-alive。
- **反向代理**：请求与响应全程流式转发，不聚合；支持 SSE；清洗 hop-by-hop 头；改写 `Host`、追加 `X-Forwarded-For`；connect / read / send 三段超时；502/504 映射；双向背压。
- **负载均衡**：`upstream` 分组、`weight` 权重、平滑加权轮询，游标按 EventLoop 隔离。
- **配置与路由**：`nginx.conf` 子集解析，继承在解析期折叠；`server_name` 与 `location` 的选择顺序照抄 nginx；未知指令直接报 `文件:行:列`，不会静默忽略。
- **线程模型**：boss 1 线程 + worker 等于 CPU 核数的 EventLoop；反代前后端 Channel 固定在同一个 EventLoop，链路内无锁。

## 快速开始

要求 **JDK 8** 和 Maven。构建：

```bash
mvn -B clean package
```

### 直接起一个静态服务器

```bash
java -jar target/nginx4j-0.1.0-SNAPSHOT.jar --port 8080 --root webroot
```

打开 `http://127.0.0.1:8080/`。命令行还支持 `--index`、`--autoindex`、`--keep-alive-timeout`、`--worker-threads`。

### 用配置文件启动

把下面的内容保存为仓库根目录的 `nginx.conf`：

```nginx
worker_processes 2;
events { worker_connections 128; }

http {
    keepalive_timeout 5s;
    proxy_connect_timeout 1s;
    proxy_read_timeout 10s;
    proxy_send_timeout 10s;

    upstream backend {
        server 127.0.0.1:9000 weight=1;
        server 127.0.0.1:9001 weight=2;
    }

    server {
        listen 8080;
        root ./webroot;
        index index.html;

        location /api/ {
            proxy_pass http://backend;
        }
    }
}
```

启动：

```bash
java -jar target/nginx4j-0.1.0-SNAPSHOT.jar -c nginx.conf
```

几点说明：

- `root` 是相对 **conf 文件所在目录** 解析的；上面的配置假设 `nginx.conf` 就在仓库根目录。
- `-c` 与 `--port`、`--root`、`--index`、`--autoindex`、`--keep-alive-timeout` 互斥，避免两套真值。
- `proxy_pass` 只允许写在 `location` 里；`upstream` 只允许写在 `http` 里。
- `proxy_pass` 目前不支持带 URI 的写法（前缀替换），原请求路径会原样转发给上游。
- 上游主机在配置解析期完成 DNS 解析，运行期不再查 DNS。
- 示例里的 `127.0.0.1:9000`、`127.0.0.1:9001` 需要先有可用的上游服务，否则代理会返回 502。

## 支持的配置子集

表外指令一律报错，并指出 `文件:行:列`。

| 上下文 | 指令 |
|---|---|
| main | `worker_processes`、`events { }`、`http { }` |
| events | `worker_connections` |
| http | `keepalive_timeout`、`root`、`index`、`autoindex`、`upstream { }`、`proxy_set_header`、三条 `proxy_*_timeout`、`server { }` |
| server | `listen`、`server_name`、`root`、`index`、`autoindex`、`keepalive_timeout`、`proxy_set_header`、三条 `proxy_*_timeout`、`location { }` |
| location | `root`、`index`、`autoindex`、`keepalive_timeout`、`proxy_pass`、`proxy_set_header`、三条 `proxy_*_timeout` |
| upstream | `server ADDR[:PORT] [weight=N]` |

限制：目前只支持单一监听端口；不支持变量（`$host` 等）、`alias`、`try_files`、嵌套 `location`、多端口绑定。

## 测试

```bash
mvn -B test
```

当前用例覆盖静态文件语义、`nginx.conf` 解析与路由、反向代理的流式转发、SSE、头清洗、502/504、加权轮询和连接计数等。

## 限制

- 上游连接一请求一条、响应结束即关，暂不支持连接池和 `proxy_http_version`。
- 暂不支持失败重试、被动健康检查、`least_conn` / `ip_hash`、draining。
- 暂不支持 TLS/SNI、HTTP/2、gzip、`limit_req`、`access_log`、WebSocket。
- 暂不支持 `rewrite` / `map` / `proxy_cache`、带 URI 的 `proxy_pass`、`alias`、`try_files`、变量、多监听端口。
- 定位：轻量实现，目标是覆盖常用链路，而不是复刻 nginx 的全部功能。

## 开发文档

- [PLAN.md](./PLAN.md)：设计与实现细节。
- [Agent.md](./Agent.md)：技术栈与工程约束。

## 许可证

仓库目前还没有 `LICENSE` 文件。
