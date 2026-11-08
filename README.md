# WiFi 扳手

厂家 Wi‑Fi 扭矩扳手的 Android 配套软件，用于浮置板钢弹簧隔振器的顶升测力与调频采集：实时扭矩可视化、远程控制扳手、带原生图表的 Excel 导出。平衡点算法待积累足够实测数据后再做。

## 下载

[![最新版本](https://img.shields.io/github/v/release/1837620622/wifi-wrench-android)](https://github.com/1837620622/wifi-wrench-android/releases/latest)

到 [最新 Release](https://github.com/1837620622/wifi-wrench-android/releases/latest) 下载 APK（附 SHA256 校验）。尚未完成真机控制验证。

## 使用

1. 在系统设置里连上扳手 Wi‑Fi，打开 App 自动连接。
2. 点顶栏「板号/测点」填好板号和测点（青色可改；开始采集后变白锁定）。
3. 先开「远程」，再正转 / 反转（需确认）；「停止扳手」随时可点。
4. 「导出 Excel」生成带原生图表的 `.xlsx`，概要页含板号、测点，文件名为 `WifiWrench_R{记录号}_{板号}_{测点}_{开始时间}.xlsx`。
5. 结束会话不等于停止扳手。

<p align="center">
  <img src="docs/design-renders/01-workbench.png" alt="横屏主台" width="720" />
</p>

## 文档

- [项目背景](docs/项目背景.md)
- [设计规范](docs/设计规范.md)（UI 交接以此为准）
- [开发接手说明](docs/开发接手说明.md)
- [验收对照](docs/验收对照.md)
- [界面与导出优化方向](docs/界面与导出优化方向.md)（协议功能码、导出结构）
- `最新协议.docx`：厂家协议原文，勿改

## 构建

Kotlin + Jetpack Compose，工程在 `WifiWrenchAndroid/`。由 GitHub Actions 构建，`versionCode = 1000 + run_number`。

```shell
cd WifiWrenchAndroid
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## 权利

软件著作权归传康Kk（GitHub `1837620622`）所有，当前免费供应。扳手硬件与协议原文权利归设备厂家。
