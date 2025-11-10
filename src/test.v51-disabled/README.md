# v5.1 Disabled Tests

这些测试文件使用了 v5.1 中已删除的旧类，需要重写以适配新架构：

## 旧P2P层测试 (已被 xdagj-p2p 模块替换)
- CapabilityTest.java - Capability 类已删除
- ConnectionLimitHandlerTest.java - 旧连接管理已删除
- FrameTest.java - Frame 类已删除  
- NodeManagerTest.java - NodeManager 已删除
- ReasonCodeTest.java - ReasonCode 已删除
- HelloMessageTest.java - 旧P2P消息类
- InitMessageTest.java - 旧P2P消息类
- WorldMessageTest.java - 旧P2P消息类

## 其他需要适配的测试
- XdagCliTest.java - 需要更新使用 DagKernel
- RocksdbKVSourceTest.java - 需要更新数据库名称
- WhliteListTest.java - 白名单功能需要更新

## TODO
这些测试需要基于 v5.1 新架构重写，使用：
- DagKernel 替代 Kernel
- xdagj-p2p 模块的新P2P接口
- AccountStore 替代 AddressStore
- DagStore 替代 BlockStore
