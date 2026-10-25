# 视频自动翻篇（Android）

**作者：剑圣不是易无极**  · 版本 1.0.0

学习通为默认目标应用（`com.chaoxing.mobile`），也可在首页切换其他已安装的应用。

## 当前实现

- 基于 Android 无障碍服务识别前台目标 App 的“下一篇”控件并执行 `ACTION_CLICK`，失败时向按钮中心发送点击手势。
- 尝试通过“播放结束”“重播”等明确界面提示或可访问 SeekBar 结束状态识别视频结束。
- 可选启用安卓媒体会话检测（需要额外启用通知使用权；很多 WebView 视频不暴露媒体会话）。
- 如识别不到，通过前台计时兜底翻篇。默认 **5 分钟**，可选 3/5/10/15/20/30/45/60/90/120 分钟。
- 前台非目标 App、息屏、找不到下一篇按钮（且未手动打开坐标模式）时暂停计时。
- 手动重置计时、测试下一篇、设置坐标、查看尝试次数和状态。
- 本地运行，无广告、无联网权限、无需 Root。

> **重要限制**：Android 无障碍服务无法普遍读取第三方 App 内嵌视频的真实播放状态。本工具只能识别该 App 在无障碍树/媒体会话中提供的信息。它既不能保证每个视频都准确识别结束，也不保证点击后的学习记录会被平台认可。**5 分钟备用翻篇不判断视频是否真正播完；长视频可能被提前跳过**。如果你要完整观看，请关闭“备用计时翻篇”，或设置长于视频时长的等待时间。

## 如何编译 APK

此压缩包是完整 Android Studio 工程源码，而不是已经编译的 APK。开发环境内没有 Android SDK，因此这里未能生成或安装测试 APK。

### 方式一：Android Studio（推荐）

1. 解压，在 Android Studio 选择 **Open**，选中 `auto_next_android` 目录。
2. 使用 JDK 17，等待 Gradle 同步（需联网下载 Gradle、Android Gradle Plugin、SDK 35）。
3. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
4. 找到 `app/build/outputs/apk/debug/app-debug.apk`，传到手机安装。

### 方式二：GitHub Actions 无本地电脑 Android Studio

1. 在 GitHub 创建私有仓库，上传压缩包解压后的完整工程目录（**保留 `.github/workflows/build-apk.yml`**）。
2. 进入仓库 **Actions → Build Android APK → Run workflow**；也可在 `main` 分支 push 后自动触发。
3. 等运行成功，在该工作流底部 **Artifacts** 下载 `video-auto-next-debug-apk`。
4. 解压获得 `app-debug.apk`，安装到手机。GitHub 的自动构建需要正常联网。

## 首次运行

1. 打开“视频自动翻篇”。
2. 打开 **开启无障碍权限**，在系统“已下载的应用”中启用“视频自动翻篇辅助服务”。部分手机对侧载 App 需在应用详情页先允许“受限设置”。
3. 保持默认目标应用“学习通”，若实际使用其他 App 则改为正确目标。
4. 开启“自动翻篇”，进入学习通课程视频页，保证页面底部有“下一篇”。
5. 默认识别失败后累计 5 分钟尝试一次；如果视频时长更长，请调整时间或关闭计时兜底。
6. 如果按钮文字无法被读取，可自行开启“找不到按钮时按坐标点击”，先设置按钮所在坐标。这种模式可能在其他页面误触，请谨慎使用。

## 结构

- `app/src/main/java/com/study/autonext/MainActivity.java`：UI 和设置。
- `app/src/main/java/com/study/autonext/AutoNextService.java`：无障碍监测、计时、点击手势。
- `app/src/main/java/com/study/autonext/MediaObserverService.java`：可选通知监听权限。
- `app/src/main/java/com/study/autonext/AdvancePolicy.java`：纯 Java 计时与进度条件。
- `.github/workflows/build-apk.yml`：云构建 APK 工作流。
