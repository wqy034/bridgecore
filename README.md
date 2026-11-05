# 舰桥核心 BridgeCore

把 Create: Aeronautics 的物理结构变成一艘可驾驶的船：星舰飞控、火控、FE 能量体系、全息姿态仪 HUD，以及手柄、键盘、红石和 CC:Tweaked 输入。

当前版本：0.1.2，Minecraft 1.21.1 / NeoForge。

## 功能概览

- **飞行控制**：关闭、增稳和星舰三种模式；支持推力档位、姿态保持、自平衡、高度跟随和重力补偿。
- **火控**：多炮塔配对、校准、目标锁定、频道任务与齐射控制。
- **能量系统**：动量分配器组成整船 FE 能量池，能量提取器将燃料或外部 FE 接入能量池。
- **全息 HUD**：姿态、航向、速度、档位、高度、能量与目标锁定显示。
- **多种输入**：红石打字机、Create Tweaked Controllers 手柄、红石代理和 CC:Tweaked 外设。
- **显示与自动化**：全息画布、拼屏和 CC 外设通道。

## 安装与运行依赖

必需：

| 组件 | 版本 | 来源 |
|---|---|---|
| Minecraft | 1.21.1 | — |
| NeoForge | 21.1.242（开发验证版本） | https://neoforged.net/ |
| Sable | 2.0.3 | https://modrinth.com/mod/sable/versions |
| Create | 6.0.10 | https://modrinth.com/mod/create/versions |
| Create: Aeronautics bundled | 1.3.0 | https://modrinth.com/mod/create-aeronautics/version/w7zlLnea |

可选：

- CC:Tweaked 1.120.0：CC 外设、显示与自动化接口。
- Create Tweaked Controllers 1.21.1-1.2.7：手柄输入。
- Create Big Cannons: Armature / `fe_railgun` 1.7.0-alpha：电磁炮集成；未安装时不影响其他功能。
- Create: Interiors 0.6.1：本地整合环境使用，并非 BridgeCore 编译依赖。

将模组及所需依赖放入 `mods/`。与 Quark 共存时，如遇 Sable 内嵌旧版 Veil 引起的启动冲突，请安装独立版 Veil 4.3.2 或更高版本。

## 快速上手

1. 使用 Create: Aeronautics 将结构物理化。
2. 在船上放置一个舰桥核心，并按需放置动量分配器和能量提取器。
3. 为提取器添加燃料，或通过外部 FE 为分配器供电。
4. 右键舰桥核心，在状态页将模式切换为“星舰”。
5. 配置键盘、手柄、红石或 CC 输入通道后起飞。

CC:Tweaked 外设提供统一的 `list()`、`read(name)`、`write(name, value)` 和 `readAll()` 方法；核心与分配器的外设类型分别为 `bridgecore_core` 和 `bridgecore_distributor`。

## 从源码构建

需要 JDK 21。第三方 JAR 不随仓库再分发；请按下表将对应文件放入本地 `devlibs/`。该目录已被 `.gitignore` 排除。

| 文件名 | 用途 | 获取来源 |
|---|---|---|
| `cc-tweaked-1.21.1-forge-1.120.0.jar` | 编译、测试 | [CC:Tweaked 1.120.0](https://modrinth.com/mod/cc-tweaked/version/8XEJbAee)；需使用 NeoForge 工件 |
| `fe_railgun-1.7.0-alpha.jar` | 可选电磁炮 API 编译 | [Create Big Cannons: Armature v1.7.0-alpha](https://github.com/wqy034/cbc-armature/releases/tag/v1.7.0-alpha) |
| `ponder-neoforge-1.0.82+mc1.21.1.jar` | 编译类型层次解析 | Create 6.0.10 发布工件中的 Ponder 1.0.82，或对应上游发布件 |
| `simulated-nested-neoforge-1.21.1-1.3.0.jar` | 编译 | 从 Create: Aeronautics bundled 1.3.0 的内嵌 JAR 中提取 |
| `create-aeronautics-bundled-1.21.1-1.3.0.jar` | 本地运行 | [Create: Aeronautics 1.3.0](https://modrinth.com/mod/create-aeronautics/version/w7zlLnea) |
| `sable-neoforge-1.21.1-2.0.3.jar` | 本地运行 | [Sable 版本页](https://modrinth.com/mod/sable/versions) |
| `interiors-1.21.1-neoforge-0.6.1.jar` | 本地整合运行环境 | [Create: Interiors](https://www.curseforge.com/minecraft/mc-mods/interiors) |

Windows Git Bash：

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.12.1"
GRADLE_OPTS="-Dfile.encoding=GBK" ./gradlew build
```

其他环境设置好 `JAVA_HOME` 后运行 `./gradlew build`（Windows）或 `gradlew build`。产物位于 `build/libs/`。

## 许可证与致谢

BridgeCore 使用 [MIT License](LICENSE)。上游版权与许可说明见 [NOTICE](NOTICE)，字体许可证随资源文件保留。

致谢：

- [shao's Flight Control with CC & VS](https://github.com/super95shao/shao-s-Flight-Control-with-cc-vs)：飞控核心控制律。
- [FireControl](https://github.com/super95shao/FireControl)：炮控、四元数与弹道数学。
- [Create: Fire Control](https://www.curseforge.com/minecraft/mc-mods/create-fire-control)：轴承稳定与 AxisLock 机制。
- [Tom's Peripherals](https://github.com/tom5454/Toms-Peripherals)：显示屏实现参考。
- VoidPower：同步模式与全息屏设计参考；未复制其代码。
