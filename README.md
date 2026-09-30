# 三阶段提交（3PC）协调组件

内存模拟的三阶段提交（canCommit / preCommit / doCommit）协调组件，含协调者、参与者、
持久化状态存储与完整事务日志。Java 17 + Maven，无外部运行时依赖。

## 运行

```bash
mvn -q verify   # 或 ./mvnw -q verify
```

## 组件结构（`com.example.gsb.tpc`）

| 类 | 职责 |
|----|------|
| `Coordinator` | 三阶段推进、失败中止、崩溃恢复续推、故障参与者补偿 |
| `Participant` | 参与者状态机、幂等响应、协调者失联时的超时自决 |
| `StateStore` / `InMemoryStateStore` | 持久化存储接口与内存实现，模拟 WAL/磁盘 |
| `TransactionLog` / `LogEntry` | 事务日志：各阶段时间戳、参与者响应、最终决定 |
| `CoordinatorRecord` / `ParticipantRecord` | 双方各自持久化的阶段状态 |

## 协议流程

1. **canCommit**：协调者询问所有参与者是否可提交。任一参与者投 NO 或不可达，
   协调者做出中止决定并通知全员 `abort` 回滚。
2. **preCommit**：全员 YES 后进入预提交。任一参与者预提交失败或不可达，同样中止并全员回滚。
3. **doCommit**：预提交全员 ACK 后，协调者先持久化 COMMIT 决定，再通知全员真正提交。

## 超时决策规则（协调者失联时）

参与者等待协调者指令超时后，按所处状态自行决策，不再无限阻塞：

- **READY（已投 YES，未收到 preCommit）→ 中止**：此时无法确认其他参与者是否都投了 YES，
  中止是唯一安全选择。
- **PRE_COMMITTED（已确认预提交，未收到 doCommit）→ 自行提交**：能进入预提交，
  说明协调者已收到全员 YES 且预提交已获确认，提交方向已确定，继续提交不会破坏一致性。

### 为什么比两阶段提交（2PC）阻塞更少

2PC 中参与者在投票 YES 后就进入“不确定窗口”：协调者若在此时崩溃，参与者既不敢提交
（可能有人投了 NO）也不敢中止（可能协调者已决定提交），只能持有锁一直阻塞到协调者恢复。
3PC 用 preCommit 阶段把这个不确定窗口拆成两段：

- 投 YES 后、收到 preCommit 前超时 → 直接中止（此时协调者不可能已做出提交决定，
  因为提交决定要求全员预提交 ACK）；
- 预提交后超时 → 直接提交（全员 YES 且预提交成功是提交决定的前置条件，方向已锁定）。

因此无论参与者在哪个阶段超时，都能**有界时间内自行得出与全局一致的决定**，
只有协调者恢复前的短暂窗口内状态可能暂时不一致，恢复后通过补偿收敛，
而不是像 2PC 那样无限期持锁等待。

## 崩溃恢复

- **协调者重启**：每个阶段在发消息前先持久化到 `StateStore`。新协调者实例调用
  `recover(txId, participants)` 按持久化阶段续推：
  - `COMMIT`/`COMMITTED`：向未确认者补发 doCommit；
  - `PRE_COMMIT`：全员预提交 ACK 已齐则继续提交，否则安全中止；
  - `CAN_COMMIT`/`STARTED`：未做决定，安全中止。
- **参与者重启**：`restart()` 返回全新实例，内存状态不保留，仅从持久化 store 恢复；
  协调者恢复流程会把缺失的 doCommit/abort 补发给它，最终全员状态一致。

## 幂等

重复的 `preCommit` / `doCommit` / `abort` / `canCommit` 返回与首次相同的结果，
事务不会重复生效（`applyCount` 恒为 1）；协调者对已终结事务重复 `execute` 直接返回原结果。

## 事务日志

`TransactionLog` 记录每个事件的 `Instant` 时间戳、事务 ID、行为者与描述，
覆盖阶段进入、每张投票、每次预提交/提交响应、超时自决、恢复补偿与最终结果。

## 测试覆盖（`ThreePhaseCommitTest`，8 个用例）

1. 全员同意 → 三阶段全部走完并提交；
2. 任一参与者拒绝 → 中止并全员回滚；
3. 预提交失败 → 已预提交的参与者也被回滚；
4. READY 状态超时 → 参与者自行中止；
5. PRE_COMMITTED 状态超时 → 参与者自行提交；
6. 协调者重启 → 从持久化的 PRE_COMMIT 阶段续推至提交；
7. 重复 preCommit/doCommit → 同一结果且只生效一次；
8. 参与者在 doCommit 前崩溃 → 重启补齐后全员状态一致。
