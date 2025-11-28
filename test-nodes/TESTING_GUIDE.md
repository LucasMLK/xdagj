# XDAG Node Testing Guide

## ⚠️ 关键注意事项

### XDAG时间系统和Epoch周期（重要！）
**XDAG使用非标准时间精度，不要假设epoch是严格的64秒！**

**TimeUtils定义**（`src/main/java/io/xdag/utils/TimeUtils.java`）：
- **时间精度**：1/1024秒，不是毫秒
- **Epoch计算**：`epoch_number = XDAG_timestamp >> 16`
- **Epoch周期**：2^16个XDAG时间单位 = 65536 * (1/1024)秒 = **精确64000毫秒**

**关键公式**：
```java
// 毫秒 -> XDAG时间戳
XDAG_epoch = (milliseconds * 1024) / 1000

// XDAG时间戳 -> 毫秒
milliseconds = (XDAG_epoch * 1000) / 1024

// Epoch编号 -> Epoch结束时间（毫秒）
epochEndMs = ((epochNumber << 16) | 0xffff) * 1000 / 1024
```

**测试验证结果**（`test_epoch_precision.py`）：
- ✅ 所有epoch边界之间间隔精确为64000ms
- ✅ 整数除法在epoch边界不造成精度损失
- ⚠️ 任意毫秒值往返转换会有约1ms的舍入误差

**代码规范**：
- ✅ **必须使用TimeUtils方法**计算epoch时间，不要硬编码64000ms
- ✅ EpochTimer正确实现：动态计算epoch duration（第128-129行）
- ❌ 不要假设epoch严格等于64秒墙上时钟

### Jar文件部署机制
**xdag.sh的jar加载逻辑**（第6行和45行）：
```bash
XDAG_JARNAME="xdagj-${XDAG_VERSION}-executable.jar"
-cp .:${XDAG_JARNAME}
```

**关键点**：
- ✅ Jar文件必须在**节点目录**下：`test-nodes/suite1/node/xdagj-1.0.0-executable.jar`
- ✅ 文件名必须精确匹配：`xdagj-1.0.0-executable.jar`
- ⚠️ **distribution.zip解压后的xdagj-1.0.0/目录中的jar不会被使用！**
- ⚠️ **必须将jar复制到节点根目录才能生效**

## 📋 正确的部署流程

### 方式1：直接复制jar（推荐，最简单）
```bash
# 1. 在项目根目录编译
cd /Users/reymondtu/dev/github/xdagj
mvn clean install -DskipTests -q

# 2. 进入节点目录并清理
cd test-nodes/suite1/node
kill $(cat xdag.pid) 2>/dev/null && sleep 2
rm -rf devnet/rocksdb devnet/reputation logs/*.log xdag.pid node1.log

# 3. 复制最新jar（直接覆盖）
cp ../../../target/xdagj-1.0.0-executable.jar .

# 4. 验证jar时间戳（确保是最新的）
ls -lh xdagj-1.0.0-executable.jar

# 5. 启动节点
./start.sh
```

### 方式2：从distribution.zip部署（需要额外步骤）
```bash
# 1-2. 同方式1

# 3a. 解压distribution
unzip -q ../../../target/xdagj-1.0.0-distribution.zip

# 3b. 复制jar到节点根目录（关键步骤！）
cp xdagj-1.0.0/xdagj-1.0.0-executable.jar .

# 3c. 删除解压目录（可选，保持目录整洁）
rm -rf xdagj-1.0.0

# 4-5. 同方式1
```

## ✅ 验证步骤

### 1. 验证BUG-CONSENSUS-004修复
启动节点后等待5秒，检查EpochTimer日志：
```bash
sleep 5 && grep "EpochTimer starting" logs/xdag-info.log
```

**预期输出**：
```
EpochTimer starting: current_epoch=X, target_epoch=Y, target_end=...ms, initial_delay=...ms, epoch_duration=64000ms
```

**验证点**：
- ✅ 日志格式包含`current_epoch`和`target_epoch`（新格式）
- ✅ `initial_delay` < 64000ms（64秒）
- ❌ 如果是旧格式`current_epoch_num`或`initial_delay` > 64000ms，说明jar未更新

### 2. 验证后备矿工工作
```bash
grep "Backup miner found solution" logs/xdag-info.log | head -3
```

**预期输出**：
```
✓ Backup miner found solution for epoch X: difficulty=0x...
```

### 3. 验证Epoch边界事件
等待70秒后检查：
```bash
grep "═══════════ Epoch.*ended" logs/xdag-info.log | tail -5
```

**预期输出**：
- 每64秒触发一次epoch结束事件
- 连续的epoch编号（无跳过）

### 4. 验证区块高度增长
```bash
curl -s http://127.0.0.1:10001/api/stats | jq '.height'
```

## 🐛 常见问题排查

### Q1: Jar文件明明更新了，但日志还是旧格式？
**原因**：Jar文件不在正确位置或名称不对

**排查**：
```bash
cd test-nodes/suite1/node
ls -lh xdagj-1.0.0-executable.jar  # 检查时间戳
md5 xdagj-1.0.0-executable.jar     # 检查文件hash
md5 ../../../target/xdagj-1.0.0-executable.jar  # 对比源文件
```

**解决**：重新复制jar到节点目录

### Q2: Initial delay > 64秒
**原因**：使用了旧代码

**解决**：
1. 确认源代码修改正确：`grep "target_epoch" src/main/java/io/xdag/consensus/epoch/EpochTimer.java`
2. 重新编译：`mvn clean compile -DskipTests -q`
3. 重新复制jar

### Q3: Epoch被跳过
**原因**：EpochTimer初始延迟计算错误（BUG-CONSENSUS-004未修复）

**症状**：
- 日志显示epoch X和epoch X+2，跳过了epoch X+1
- Initial delay > 64000ms

**解决**：应用BUG-CONSENSUS-004修复

### Q4: 后备矿工报错"no candidate block"
**原因**：EpochConsensusManager未注入BlockGenerator（BUG-CONSENSUS-003未修复）

**验证**：
```bash
grep "Cannot start backup mining: no candidate block" logs/xdag-info.log
```

## 📊 已修复的BUG

### BUG-CONSENSUS-003: 候选区块为null
- **文件**：`EpochConsensusManager.java`, `DagKernel.java`
- **修复**：为EpochConsensusManager添加BlockGenerator依赖注入
- **验证**：后备矿工成功启动并找到solution

### BUG-CONSENSUS-004: EpochTimer初始延迟计算错误
- **文件**：`EpochTimer.java:107-133`
- **修复**：初始延迟使用当前epoch结束时间，而非下一个epoch
- **验证**：initial_delay < 64秒，无epoch跳过

## 🎯 当前测试目标

**Phase 4: 集成测试**
- Suite 1.1: Epoch时间精度 ✅
- Suite 1.2: 后备矿工激活 ✅
- Suite 1.3: Solution收集 ⏳ 进行中
- Suite 1.4: 区块导入和高度增长 ⏳ 待验证

## 📝 测试检查清单

部署前：
- [ ] 修改代码后运行`mvn clean install`
- [ ] 检查BUILD SUCCESS
- [ ] 复制jar到节点目录（不是解压目录！）
- [ ] 验证jar文件时间戳

启动后（等待5秒）：
- [ ] 检查EpochTimer日志格式（新格式带target_epoch）
- [ ] 验证initial_delay < 64000ms
- [ ] 确认后备矿工成功启动

运行中（等待70秒）：
- [ ] 验证epoch边界事件（每64秒）
- [ ] 确认无epoch跳过
- [ ] 检查区块导入日志
- [ ] 验证区块高度增长
