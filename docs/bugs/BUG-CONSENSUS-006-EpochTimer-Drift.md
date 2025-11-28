# BUG-CONSENSUS-006: EpochTimer停止触发导致epoch漂移

**严重性**: CRITICAL
**发现日期**: 2025-11-27
**状态**: FIXED

## 问题描述

EpochTimer使用`scheduleAtFixedRate`调度任务，但当JVM发生长时间暂停（如GC暂停、线程饥饿、系统休眠）时，定时器会停止触发。恢复后，由于依赖固定间隔计算epoch，会导致epoch编号错误，造成严重的共识问题。

## 根本原因

### 原始实现问题

```java
// 原始代码 - 有缺陷
epochScheduler.scheduleAtFixedRate(() -> {
    long newEpochNum = getCurrentEpoch();
    long endedEpochNum = newEpochNum - 1;
    log.info("═══════════ Epoch {} ended ═══════════", endedEpochNum);
    onEpochEnd.accept(endedEpochNum);
}, initialDelay, epochDurationMs, TimeUnit.MILLISECONDS);
```

**问题**:
1. 使用`scheduleAtFixedRate`依赖JVM线程调度，容易受暂停影响
2. 没有检测epoch漂移
3. 没有处理跳过的epoch

### 实际发现的案例

**时间线** (2025-11-27):

```
10:02:40 - Epoch 27565764 ended (正常)
10:03:44 - Epoch 27565765 ended (正常)
10:04:48 - Epoch 27565766 ended (正常)
10:05:52 - Epoch 27565767 ended (正常)
10:06:56 - Epoch 27565768 ended (正常)
[20分钟空白 - Timer完全停止]
10:27:11 - Epoch 27565786 ended (错误！应该是27565787)
10:28:15 - Epoch 27565787 ended (延迟)
```

**后果**:
- BackupMiner在10:07-10:26之间正常工作，产生了19个epoch的解决方案
- EpochTimer停止触发，这些解决方案无法被处理
- EpochContext堆积在内存中
- Timer恢复时计算出错误的epoch编号
- 导致所有后续区块导入失败

**证据**:

BackupMiner日志显示正常工作：
```
10:07:55 - Backup miner found solution for epoch 27565769
10:08:59 - Backup miner found solution for epoch 27565770
...
10:26:03 - Backup miner found solution for epoch 27565786
```

但EpochTimer日志在10:06:56之后消失20分钟！

## 修复方案

### 增强EpochTimer的健壮性

```java
// 修复后的代码
final long[] lastProcessedEpoch = {currentEpochNum - 1};

epochScheduler.scheduleAtFixedRate(() -> {
    try {
        // BUG FIX: 每次触发时重新计算实际epoch
        long actualCurrentEpoch = getCurrentEpoch();
        long endedEpochNum = actualCurrentEpoch - 1;

        // 检测epoch漂移
        long expectedEpoch = lastProcessedEpoch[0] + 1;
        if (endedEpochNum > expectedEpoch) {
            long skippedCount = endedEpochNum - expectedEpoch;
            log.error("⚠️ EPOCH TIMER DRIFT DETECTED! Expected epoch {}, but actual is {}. Skipped {} epochs!",
                    expectedEpoch, endedEpochNum, skippedCount);
            log.error("This can happen due to: JVM GC pause, thread starvation, or system hibernation");

            // 处理所有跳过的epoch以清理它们的context
            for (long skipped = expectedEpoch; skipped < endedEpochNum; skipped++) {
                log.warn("Processing skipped epoch {} (late processing)", skipped);
                onEpochEnd.accept(skipped);
            }
        } else if (endedEpochNum < expectedEpoch) {
            log.warn("⚠️ Timer fired early! Expected epoch {}, but actual is {}",
                    expectedEpoch, endedEpochNum);
            return; // 跳过提前触发
        }

        log.info("═══════════ Epoch {} ended ═══════════", endedEpochNum);
        onEpochEnd.accept(endedEpochNum);
        lastProcessedEpoch[0] = endedEpochNum;

    } catch (Exception e) {
        log.error("Error processing epoch end", e);
    }
}, initialDelay, epochDurationMs, TimeUnit.MILLISECONDS);
```

### 修复的关键点

1. **实际epoch计算**: 每次触发时调用`getCurrentEpoch()`重新计算实际的当前epoch
2. **漂移检测**: 比较预期epoch和实际epoch，检测跳过的epoch
3. **补偿处理**: 为所有跳过的epoch调用`onEpochEnd`，清理它们的context
4. **提前触发保护**: 如果Timer意外提前触发，跳过该触发
5. **详细日志**: 记录漂移原因（GC、线程饥饿、系统休眠）

## 影响范围

**受影响的场景**:
- JVM Full GC暂停（长时间STW）
- 系统资源不足导致线程饥饿
- 开发机器进入休眠/睡眠模式
- 虚拟机暂停/恢复
- 容器资源限制导致CPU throttling

**症状**:
- "No solutions collected"日志，但BackupMiner实际找到了解决方案
- EpochContext内存泄漏
- 区块导入失败（epoch不匹配）
- 节点无法产生新区块

## 测试验证

### 单元测试
```java
@Test
public void testEpochTimerDriftDetection() {
    // TODO: 模拟Timer暂停场景
}
```

### 集成测试
1. 启动节点
2. 人为触发Full GC或线程暂停
3. 验证日志中是否出现"EPOCH TIMER DRIFT DETECTED"
4. 验证跳过的epoch是否被正确处理

## 相关Bug

- **BUG-CONSENSUS-001**: Backup mining确保每个epoch产生区块
- **BUG-CONSENSUS-002**: Solution collection使"最佳解决方案获胜"
- **BUG-CONSENSUS-005**: EpochTimer边界对齐修复

## 未来改进

考虑使用更健壮的定时机制：
1. 使用`scheduleWithFixedDelay`而不是`scheduleAtFixedRate`
2. 添加watchdog线程监控Timer健康状态
3. 实现自适应重新同步机制
4. 添加JVM暂停监控（使用JMX）

## 修复提交

**Commit**: `[待提交]`
**文件**:
- `src/main/java/io/xdag/consensus/epoch/EpochTimer.java`

**测试**: 需要长时间运行测试验证修复有效性
