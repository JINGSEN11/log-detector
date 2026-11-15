# 限流改造：从单机窗口到 Redis 分布式滑动窗口

## 1. 改造前的问题

原实现 `RequestRateLimiter` 用进程内 `ArrayDeque` 记录滑动窗口：

- 计数存在 JVM 堆里，**重启即清零**；
- 多实例部署时各算各的，N 个实例等于阈值放大 N 倍；
- 日志接口、研判接口、模型测试接口共用同一张 `HashMap`，key 上限 10000 后直接拒绝所有请求。

单实例演示看不出问题，一旦水平扩容，限流就形同虚设。

## 2. 方案

### 2.1 数据结构与原子性

用 Redis ZSET 存"窗口内的命中时间戳"：score 是毫秒时间戳，member 是唯一串（时间戳 + 随机数，避免同毫秒覆盖）。

四个操作必须在同一原子单元里完成，否则并发下会出现"计数没到但被拒"或"超发"：

1. `ZREMRANGEBYSCORE` 清掉窗口外的旧记录；
2. `ZCARD` 读取当前计数；
3. 判断是否达到阈值；
4. `ZADD` 写入本次命中并设置 `PEXPIRE`。

这四步放在一个 Lua 脚本（`lua/sliding_window.lua`）里，由 Redis 单线程执行，天然原子，不需要额外加分布式锁。

脚本支持两种模式：

- `peek`：只清理并返回计数，用于登录前的预检查；
- `hit`：清理 + 判断 + 占用配额，返回 `{allowed, count, retryAfterSeconds}`。

### 2.2 阈值语义保持不变

| 场景 | key | 阈值 | 说明 |
|---|---|---|---|
| 登录失败 | `rate:login:{ip}` | 8 次 / 5 分钟 | 只对 401/403 计数，登录成功不计数 |
| 事件研判 | `rate:analyze:{userId}` | 11 次 / 5 分钟 | 第 12 次返回 429 |
| 模型连通性 | `rate:llm:{userId}` | 11 次 / 5 分钟 | 失败也计数 |

### 2.3 降级策略

Redis 不可用不能让整个登录/研判链路瘫掉：

- 连接或命令异常时，**打开 30 秒熔断**，期间直接走本地窗口，不再每个请求都等 800ms 超时；
- 熔断期间日志只打一次 `WARN`，避免刷屏；
- 熔断到期后试探一次 Redis，成功则打 `INFO` 并恢复正常计数。

本地窗口实现保留原逻辑，两个分支的阈值语义完全一致，降级前后行为可预期。

## 3. 验证

环境：本机 Docker Compose，`csys-backend` + `csys-redis` + MySQL 8，阈值 11 次/5 分钟。

### 3.1 Redis 正常路径

连续请求 `POST /api/llm/chat` 13 次：

| 请求序号 | 结果 |
|---|---|
| 1–11 | HTTP 200 |
| 12 | HTTP 429 |
| 13 | HTTP 429 |

Redis 侧核对：`KEYS rate:*` 得到 `rate:llm:1`，`ZCARD rate:llm:1` = **11**。

说明计数确实写进了 Redis，不是本地兜底。

### 3.2 Redis 宕机降级

`docker stop csys-redis` 后重复同样 13 次请求：

- 前 11 次 200，第 12 次 429 —— 限流仍然生效；
- 服务没有报错崩溃，登录、研判接口照常响应；
- 后端日志仅一条：`Redis 限流不可用，降级本地窗口 30 秒：Redis command timed out`。

### 3.3 恢复

`docker start csys-redis`，等待熔断到期后再次请求：

- 日志出现 `Redis 限流已恢复`；
- 请求重新由 Redis 计数处理。

## 4. 多实例验证

上一版文档把「多实例共享计数」列为未实测项，这次补上了。

### 实验设置

| 项 | 内容 |
|---|---|
| 实例 A | `csys-backend`，宿主机 8080 |
| 实例 B | `csys-backend-2`，宿主机 8081，同镜像同 MySQL 同 Redis |
| 验证接口 | 登录失败限流，阈值 8 次 / 5 分钟，按来源 IP 计数 |
| 请求方式 | 错误密码交替打两个实例，命中配额才 401，超出即 429 |

### 结果

| 指标 | 数值 |
|---|---|
| 实例 A 放行 | 4 次 |
| 实例 B 放行 | 4 次 |
| 合计放行 | 8 次 |
| 第 9 次 | 返回 429，两个实例都拒绝 |
| Redis 键 | `rate:login:172.20.0.1` |
| 该键 ZCARD | 8 |

### 结论

等量请求摊到两个实例，每个实例只见到 4 次，单看任何一个都远低于阈值 8，但合计第 9 次即被拒，且 Redis 计数恰好等于总放行数——**阈值是全局的，不随实例数放大**。

对照组说明：若沿用改造前的进程内窗口，两个实例各持 8 次配额，交替打满 16 次才会全部拒绝；也就是说多实例部署下限流阈值会被放大到 N 倍，这正是本次改造要解决的问题。

### 复现

脚本 `verify_multi_login.py` 放在外部，逻辑是两实例健康检查 → 清空 `rate:login:*` → 交替错误登录 → 比对 429 出现位置与 Redis 计数是否等于总放行数。

## 5. 局限与后续

- Redis 为单节点，未考虑主从切换时窗口数据的丢失；生产上应使用 Redis Cluster 或容忍秒级误差。
- 阈值目前写死在代码常量里，后续可外置到配置中心，按接口维度动态调整。
- 未做限流命中率的监控埋点，只有 429 返回码可观测。
- 多实例验证覆盖了登录限流；研判与模型连通性两条走的是同一套 `redisHit`，未单独压测。

## 6. 涉及文件

| 文件 | 说明 |
|---|---|
| `backend/src/main/resources/lua/sliding_window.lua` | 原子滑动窗口脚本 |
| `backend/src/main/java/.../security/RedisWindowLimiter.java` | Redis 执行、熔断与降级统计 |
| `backend/src/main/java/.../security/RequestRateLimiter.java` | 门面：Redis 优先、异常降级本地 |
| `backend/src/main/resources/application.yml` | `spring.data.redis` 配置与超时 |
| `docker-compose.yml` | 后端注入 `REDIS_HOST/REDIS_PORT` |
| `backend/pom.xml` | 新增 `spring-boot-starter-data-redis` |

原有 9 个相关单元测试（`RequestRateLimiterTest`、`LoginAuditRateTest`、`LlmChatRateTest`、`AnalysisRateAuditTest`）全部通过，未修改测试代码。
