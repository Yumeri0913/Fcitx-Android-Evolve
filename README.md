中文
|
[English](README.en.md)

# Fcitx Android Evolve

基于 [Fcitx5](https://github.com/fcitx/fcitx5) 的 Android 拼音输入法，专注于在AI时代进行传统手搓输入体验把打字手感做好。

本项目 fork 自 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)。

> 状态：个人项目，日常在用，尚未正式发布。暂无现成的 APK，请自行构建（见下文）。

## 与 fcitx5-android 的不同

### 外观
- 两套内置主题（浅色／深色），无边框、2 dp 圆角，青绿色回车键
- 重新调校的键盘高度：按屏幕尺寸分档的固定键盘高度（默认），也可切回屏幕高度百分比，另有 90–110% 微调
- 按键画在格子顶部，纵向间隙留在键下方；工具栏常驻，最左侧是中／英切换

### 手感
- 触摸感知纠错：用二维高斯按键模型给触摸点周围的按键打分，拼音引擎据此尝试可能误触的相邻键，
  上下行误触与左右误触同样容易纠正
- 精细调校的触摸处理：按键间滑动的迟滞判定、多指滚按时按顺序上屏、Shift 按住即大写／双击锁定、
  空格滑动移动光标、退格连删、按键预览松手后保留 100 ms
- 点按键盘顶部内边距会选中上方的候选词
- 按键震动默认开、按键音默认关

### 拼音与词库
- 优化词库导入
- 改进编码排序效率
- 大幅度优化中英混输

### 符号与表情
- 分类符号键盘
- 表情面板：最近使用与分类浏览；支持用中文或英文搜索表情（猫／cat）

### 设置
- 地球键只切换中／英；长按空格选择输入法
- 局域网剪贴板同步（HTTP 51423 端口、UDP 发现与配对）

## 安装注意

包名仍为 `org.fcitx.fcitx5.android`，与官方版相同但签名不同：安装前需先卸载官方版，
两者的数据不互通。

## 构建

### 依赖

- Android SDK Platform 与 Build-Tools 35
- Android NDK（Side by side）25 与 CMake 3.22.1，可在 Android Studio 的 SDK Manager 中安装
- [KDE/extra-cmake-modules](https://github.com/KDE/extra-cmake-modules)
- GNU Gettext ≥ 0.20（需要 `msgfmt`）

推荐版本记录在 [Versions.kt](build-logic/convention/src/main/kotlin/Versions.kt)。

### 步骤

```shell
git clone https://github.com/Yumeri0913/Fcitx-Android-Evolve.git && cd Fcitx-Android-Evolve
git submodule update --init --recursive

# macOS
brew install extra-cmake-modules gettext
# Debian/Ubuntu
sudo apt install extra-cmake-modules gettext

./gradlew :app:assembleRelease
```

libime 和 fcitx5-chinese-addons 的改动以补丁形式放在 [patches](patches) 目录，
CMake 配置时由 `patches/ApplyPatches.cmake` 自动打上，无需手动处理。

Windows、Nix 环境以及 Android Studio 常见问题，请参考
[fcitx5-android 的 README](https://github.com/fcitx5-android/fcitx5-android#build)。

## 测试

```shell
./gradlew :app:testDebugUnitTest          # 单元测试
./gradlew :app:connectedDebugAndroidTest  # 插桩测试，需要连接设备或模拟器
```

## 上游

通用的修复欢迎回馈给 [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)。
感谢 fcitx5-android 的作者和 [Fcitx](https://github.com/fcitx) 项目。

## 许可证

[LGPL-2.1](LICENSE)。
