# SMB 播放器 TV

通过 SMB 协议直接播放局域网（NAS / 共享文件夹）中视频的 **Android TV 播放器**，
由手机版 [SMB 播放器](../smbPlayer) 复刻而来，专为电视大屏与遥控器操作设计。

## 与手机版的差异

| 项 | 手机版 | TV 版 |
| --- | --- | --- |
| 最低系统 | Android 8.0（API 26） | **Android 5.0（API 21，核心库脱糖）** |
| 操作方式 | 触摸手势（滑动调进度、长按倍速、双击） | **遥控器 D-pad 全键位** |
| 截取帧 | 支持（上一帧/下一帧/导出 PNG） | **已移除** |
| 主页 | 服务器 + 收藏 | **+ 最近播放 10 条，点击续播** |
| 视频渲染 | TextureView | SurfaceView（低配盒子更流畅） |
| 主题 | 浅色/深色跟随系统 | 统一深色，全屏横屏 |

## 遥控器键位（播放页）

- **确认键**：播放 / 暂停（不弹控制条，避免左右键与按钮移动打架）
- **左右键**：快退 / 快进 10 秒（按住连发累计，松开才真正 seek，屏幕实时预览落点；不弹控制条）
- **上下键**：音量增减（系统音量条）
- **菜单键**：呼出 / 收起控制条；控制条显示后方向键在按钮间移动，确认键点击
- **返回键**：先收起控制条；再连按两次返回退出播放（提示「再按一次返回退出」，不弹窗）
- **媒体键**（播放/暂停/快进/快退/上一集/下一集）：直接支持
- 列表页：长按确认键 = 多选 / 删除 / 编辑等操作；进入文件夹后自动定位到第一条（列表滚到顶部）

## 功能

- 服务器管理（匿名 / 账密登录、完整路径粘贴直达）、共享自动扫描与切换
- 目录浏览、排序（名称/大小/时间）、多选删除、收藏路径
- 直接流式播放：ExoPlayer + 自定义 `SmbDataSource`，不落盘缓存
- 播放记录自动续播；同目录视频组成播放列表，播完自动下一个
- 同名字幕自动加载（.srt/.ass 等，叠加层渲染）
- 主页「最近播放」：最近 10 条，一键续播

## 构建

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

依赖本机 `D:\code\sdk`（JDK / Gradle 8.9 / Android SDK），签名使用 `D:\code\sdk\signing` 统一 keystore，
产物输出到 `output\app-release.apk`。

## 技术栈

Kotlin 2.0.21 · Gradle 8.9 + AGP 8.7.3 · compileSdk/targetSdk 35 · **minSdk 21** ·
Media3 / ExoPlayer 1.4.1 · smbj 0.15.0 + jcifs-ng 2.1.10 · ViewBinding + Material 3 ·
coreLibraryDesugaring（API 21 上的 java.time / java.nio.file / CompletableFuture）
