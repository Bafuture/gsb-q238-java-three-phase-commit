# 三阶段提交协议组件

Pair-wise GSB 标注任务仓库（第 16 批 / 238）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

两阶段提交在协调者崩溃时参与者会一直阻塞，我们需要三阶段提交来降低阻塞。请从零实现一个三阶段提交（canCommit、preCommit、doCommit）协调组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ），参与者与协调者都用内存对象模拟。要求：1) 支持三阶段流程：先询问所有参与者是否可提交，全部同意后进入预提交，预提交全部成功后才真正提交；2) 任一参与者拒绝时中止事务并让所有参与者回滚；3) 超时决策：在预提交阶段协调者失联时，参与者按规则决定继续提交还是中止（规则写进 README 并说明为什么比两阶段阻塞更少）；4) 支持协调者与参与者重启：重启后能根据持久化的阶段状态继续推进而不是从头开始；5) 幂等：重复的预提交与提交请求返回同一结果，不得重复生效；6) 支持参与者故障恢复后补齐状态，最终所有参与者状态一致，需有测试证明；7) 提供完整事务日志：各阶段时间、参与者响应与最终结果；8) 测试覆盖全部提交、拒绝中止、预提交阶段超时两种决策、重启续推、幂等与最终一致；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

---

# 实现说明：三阶段提交（3PC）协调组件

代码位于 `src/main/java/com/example/gsb/threepc/`，参与者与协调者均为内存对象，
协议状态写入注入的 `InMemoryDurableStore`（模拟磁盘：对象销毁后数据仍在，
用同一个 store 新建对象即视为“重启”）。

## 协议流程（canCommit → preCommit → doCommit）

1. **canCommit**：协调者向所有参与者询问是否可提交。参与者投 `YES` 后进入
   `READY`；投 `NO` 则自行中止。
2. **preCommit**：全部 `YES` 后，协调者发送预提交。参与者把 prepare 状态落盘、
   进入 `PRE_COMMITTED` 并回 `ACK`；任一 `NACK`/不可达即中止。
3. **doCommit**：全部 `ACK` 后，协调者先把“提交决策”落盘（进入 `COMMITTING`），
   再向参与者发送 doCommit；全部送达后进入终态 `COMMITTED`。
4. 任一阶段失败：决策中止并向**所有**参与者投递 abort，全部送达后进入 `ABORTED`。

协调者状态机：`CAN_COMMITTING → PRE_COMMITTING → COMMITTING → COMMITTED`，
失败路径进入 `ABORTING → ABORTED`。每个状态迁移都先落盘并追加事务日志。

## 超时决策规则（需求 3）

参与者发现协调者失联时，调用 `Participant.onCoordinatorTimeout(txId)`，
按**自身已持久化的阶段状态**单方面决策：

| 参与者当前状态 | 含义 | 超时决策 |
|---|---|---|
| `INIT` | 未投过票 | **中止**（安全的空操作） |
| `READY` | 已投 YES，尚未收到 preCommit | **中止** |
| `PRE_COMMITTED` | 已应答 preCommit，等待 doCommit | **提交** |
| `COMMITTED` / `ABORTED` | 已是终态 | 保持原结果（幂等） |

**为什么这比两阶段提交（2PC）阻塞更少？**

- 在 2PC 中，参与者投出 YES 后进入“不确定（in-doubt）”状态：它不知道协调者最终
  决定提交还是中止，锁和资源只能一直持有。此时协调者若崩溃，参与者**既不能
  单方面提交也不能单方面中止**（两种选择都可能与协调者的最终决策冲突），只能
  无限期阻塞，直到协调者恢复。
- 3PC 在投票与提交之间插入 preCommit 缓冲阶段，形成关键不变式：**“能收到
  preCommit，前提是所有参与者都投了 YES；而 doCommit 只在所有人都确认
  preCommit 后才会发出。”**
  - 处于 `PRE_COMMITTED` 的参与者知道：所有人都已同意提交、且都进入了同一阶段，
    因此超时后单方面**提交**一定与最终决策一致，不会与任何人冲突；
  - 仍处于 `READY` 的参与者知道：只要还有人没进入 preCommit，协调者就不可能已经
    决定提交，因此超时后单方面**中止**是安全的。
- 于是在“单点协调者崩溃 + 参与者存活”这一 2PC 的典型阻塞场景下，3PC 的参与者
  无需等待协调者恢复即可自行收敛，阻塞范围被显著缩小。
  （经典 3PC 在网络分区同时叠加参与者故障时仍可能出现分歧，这是协议本身的已知
  局限；本组件的参与者超时规则与协调者恢复规则保持一致：提交决策落盘后只提交，
  落盘前崩溃则中止。）

## 重启恢复规则（需求 4）

新协调者实例对事务调用 `recover(txId, participants)`，按持久化状态续推：

| 持久化状态 | 恢复动作 |
|---|---|
| `COMMITTING` | 提交决策已落盘 → 继续向未送达者投递 doCommit，全部完成后置 `COMMITTED` |
| `ABORTING` | 继续投递 abort，全部完成后置 `ABORTED` |
| `CAN_COMMITTING` / `PRE_COMMITTING` | 崩溃发生在提交决策落盘之前 → 安全中止 |
| `COMMITTED` / `ABORTED` | 直接返回持久化结果（幂等） |

参与者重启后从自己的 store 恢复阶段状态（如仍是 `PRE_COMMITTED`），协调者续推时
补齐最终决策，不会重新执行 canCommit/preCommit。

## 幂等（需求 5）

- 参与者按 `txId` 记录状态：对已 `PRE_COMMITTED` 的事务重复 preCommit 直接返回
  `ACK`；对已 `COMMITTED` 的事务重复 doCommit 直接返回 `COMMITTED`；
  prepare/commit 效果各只生效一次（`ParticipantRecord` 中的计数器证明）。
- 协调者对已终结的 `txId` 重复执行 `commit` 直接返回持久化结果，不重跑协议；
  `recover` 可任意重复调用。

## 事务日志（需求 7）

`TransactionRecord` 内嵌不可变的追加式日志，随状态一起落盘（重启后仍在）。
每条 `TransactionLogEntry` 包含：时间戳（由可注入的 `Clock` 提供）、事务 ID、
事件类型、明细。事件类型：

`TX_STARTED`、`VOTE_RECORDED`（各参与者投票）、`PHASE_ENTERED`（各阶段进入时间）、
`PRECOMMIT_REPLY_RECORDED`（各参与者预提交应答）、`DECISION_MADE`（最终决策及原因）、
`FINAL_DELIVERY`（doCommit/abort 逐个参与者的送达结果，不可达时记录待补齐）、
`RECOVERY_STARTED`、`TX_COMPLETED`（最终结果）。

## 测试覆盖（需求 8）

- `ThreePhaseCommitFlowTest`：全部同意正常提交；任一拒绝 → 全部回滚；preCommit 失败 → 中止。
- `TimeoutDecisionTest`：READY 超时中止、PRE_COMMITTED 超时提交，以及协调者失联后
  参与者单方面决策与重启续推结果一致（两种决策各一例）。
- `RestartRecoveryTest`：协调者崩溃重启后从 `COMMITTING` 续推（不重新询问）；
  参与者重启保留 `PRE_COMMITTED` 并补齐提交；参与者恢复后补齐 abort，最终一致。
- `IdempotencyTest`：重复 preCommit/doCommit 返回同一结果且只生效一次；
  重复 commit/recover 幂等。
- `TransactionLogTest`：各阶段时间戳、参与者响应、最终结果完整且重启后保留。

## 运行

```bash
mvn -q verify        # 或 ./mvnw -q verify
```
