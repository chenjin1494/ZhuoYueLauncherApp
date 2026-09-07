# 万能转发器（ZhuoYue UrlFeeder）

面向 Lenovo TB337FC 学习平板（ZUI16 / Android 13）的本地工具 App。
包名 `com.helper.urlfeeder`，纯 framework View（无 XML 资源、无 Gradle），命令行工具链构建。

## 功能一览

- **🏠 首页**：网址 → 内置白名单浏览器 / Chrome 打开；一键开网（清管控防火墙规则）；检测白名单外站点连通（bilibili/bing/qq/163）；运行日志（清空/复制）
- **📱 应用页**：搜索 + 下拉刷新（弹簧物理） + 长按详情/卸载
- **⚙️ 设置页**：界面背景 / 玻璃透明度 / 网络守护（常驻自动开网）/ Shizuku 桌面管理 / 默认浏览器与桌面 / ADB
- 液态玻璃 UI、无障碍授权入口等

详细说明见 [万能转发器使用说明.md](./万能转发器使用说明.md)。

## 目录结构

```
├── app/
│   ├── AndroidManifest.xml
│   └── src/com/helper/urlfeeder/{MainActivity,GuardService,BootReceiver,ShizukuUtil}.java
├── .github/workflows/build-apk.yml   # GitHub Actions 自动编译
├── build.sh                          # 本地/CI 通用构建脚本
└── 万能转发器使用说明.md
```

## 依赖

Shizuku API（用于 shell 级桌面管理）在构建时自动从 Maven Central 下载：
`dev.rikka.shizuku:{api,aidl,provider}:13.1.5`（解 AAR 取 classes.jar，无需提交 jar 到仓库）。

## 本地构建

需要 JDK + Android SDK（platforms;android-34 + build-tools;34.0.0）：

```bash
./build.sh urlfeeder.apk
```

## GitHub Actions 自动编译

`push` / `PR` / 手动 `workflow_dispatch` / 发 `Release` 时自动构建，
产物作为 **artifact** 上传；Release 时额外附加到发布页。

## 同步约定（重要）

本目录是**面向 GitHub 的干净源码仓库**。
开发在本地工作区进行；每次源码改动后，把以下文件同步到本仓库并提交：

```bash
# 在 ~/data 下执行（源 → 本仓库）
cp ZhuoYueLauncher/urlfeeder/AndroidManifest.xml            ZhuoYueLauncherApp/app/
cp ZhuoYueLauncher/urlfeeder/src/com/helper/urlfeeder/*.java ZhuoYueLauncherApp/app/src/com/helper/urlfeeder/
cp ZhuoYueLauncher/万能转发器使用说明.md                     ZhuoYueLauncherApp/
```

提交信息建议带版本号，如 `feat: v5.35 说明文档同步`。

## 合规说明

本工具部分能力用于规避组织白名单管控（网络/桌面），仅限本人设备技术研究使用。
