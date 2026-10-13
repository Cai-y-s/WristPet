# 腕上伙伴 WristPet（MVP 0.2.0）

这是给 OPPO Watch X3（你实测的型号 `OWW261`、Android API 30、466×466 圆屏）准备的一个离线电子宠物原型。它使用原生 Android View + Canvas 绘制，不依赖网络、图像素材、数据库或第三方 UI 库。

## 当前功能

- 圆屏深色主界面和自绘的像素感小芽角色
- 喂食：饱腹度低于 85 时才可喂食；连续点击“已经饱了”的宠物不会重复获得经验
- 摸摸：每 60 秒最多接受一次；亲密度达到 100 后不再重复增加
- 成长平衡：喂食、摸摸和小游戏共享每天最多 8 点经验，避免连续点击或反复刷小游戏让宠物瞬间成熟
- 10 秒卡点小游戏：光点经过顶部亮区时点击屏幕；命中可获得金币和经验（经验受每日上限约束）
- SharedPreferences 本地存档，关闭再打开后数据仍在
- 离线饱腹度缓慢下降，最低不会低于 20；宠物不会死亡、经验不会倒退
- 只在应用前台运行轻量重绘循环，不请求网络、位置、健康或后台服务权限

## 工程配置

- Kotlin
- `minSdk = 30`
- `compileSdk = 35`
- `targetSdk = 30`（仅用于当前个人设备侧载验证；公开发布前应重新评估目标 SDK 和分发政策）
- Android Gradle Plugin 8.10.1、Gradle 8.14.2（按当前工程配置）、Kotlin Gradle Plugin 2.2.20、JDK 17
- 包名：`com.example.wristpet`

## 在 Windows 上构建

### 1. 安装准备

1. 安装 Android Studio。
2. 在 Android Studio 的 SDK Manager 中安装 **Android SDK Platform 35** 和 Android SDK Build-Tools。
3. 确保 Android Studio 的 Gradle JDK 是 JDK 17 或更高兼容版本（Android Studio 自带的 JBR 通常可用）。
4. 让手表保持已授权的 ADB 连接；你已经能执行 `adb shell getprop ...`，因此可以先继续使用当前连接。

### 2. 打开工程

解压此 ZIP，在 Android Studio 选择 **Open**，选择解压后的 `WristPet` 文件夹，等待 Gradle 同步结束。

工程提供了 `gradlew.bat` 引导脚本：首次在 Windows 命令行使用时，如果工程本地还没有 Gradle 8.14.2，它会从 Gradle 官方分发站点下载 Gradle 8.14.2。若公司网络或代理阻止下载，可以先在 Android Studio 完成工程同步，或配置可访问 Gradle 分发站点的网络。

### 3. 生成 Debug APK

在 Android Studio 的 Terminal（确保当前目录是工程根目录）执行：

```powershell
.\gradlew.bat assembleDebug
```

构建成功后 APK 位于：

```text
app\build\outputs\apk\debug\app-debug.apk
```

你也可以在 Android Studio 中通过 **Build > Build Bundle(s) / APK(s) > Build APK(s)** 构建。

### 4. 安装到手表

在工程根目录的 PowerShell 执行：

```powershell
adb devices -l
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell monkey -p com.example.wristpet 1
```

如果 `adb devices -l` 里不止一个设备，请用 `adb -s <设备序列号> install -r ...` 指定手表。启动命令同样可以写成 `adb -s <设备序列号> shell monkey -p com.example.wristpet 1`。

也可以直接双击 `build-and-install.bat`；它会先构建再安装。如果手表不允许 APK 安装或报安装错误，请保留完整错误信息，不要 Root 手表或修改系统分区。

## 操作方式

- 主界面：点左侧“喂食”、中间“摸摸”、右侧“玩耍”。
- 小游戏：光点绕环转动；它接近圆环顶部的绿色目标区时，点击屏幕任意位置。每一圈最多计一次命中。
- 结束：点击小游戏底部“结束”，或者等待 10 秒自动结算。
- 存档：数据保存在应用私有目录；正常卸载应用会删除这份本地存档。

## 目前的限制

1. 这份工程是首个可玩原型，不是最终商用品质 UI；角色由 Canvas 基础图形绘制，后续可替换成自己制作的像素素材。
2. `OWW261` 的实际安装、全屏行为、触摸区域和侧载限制仍须在你的实机验证。能够运行 shell ADB 并不必然保证设备允许安装任意 APK。
3. `targetSdk = 30` 是为了当前 API 30 设备的个人侧载原型而设，并不代表适合公开上架。发布前应根据 OPPO 的具体分发渠道和当时政策调整。
4. 如果应用能打开但某些元素被系统手势区域遮住，请拍一张运行截图发来，再按实机可用区域调整绘制坐标。

## v0.2.0 本轮修复

1. 喂食仅在饱腹度低于 85 时生效；饱腹时重复点击不会增加经验。
2. 摸摸设置 60 秒冷却，并在亲密度达到 100 后停止生效。冷却时间保存在本地，强制结束应用也不会绕过。
3. 所有经验来源共用每日 8 点上限，达到上限后仍可互动，但不会继续增加成长经验。成熟阶段需要累计 80 点经验，因此正常情况下最快也需要 10 个自然日达到。
4. 首页显示今日已获得的成长经验。
5. 修正饱腹度计时：保留不足 20 分钟的剩余时间，避免每次存档都把饥饿计时清零。

**存档注意：** 更新安装会保留旧版本数据。如果之前已经通过连续点击把宠物升到成熟阶段，v0.2.0 不会自动降低已有经验。如需从新宠物重新测试，可以执行 `adb shell pm clear com.example.wristpet`；此操作会清除该应用的所有本地存档。

## 主要源码

- `MainActivity.kt`：Activity 生命周期和事件
- `pet/PetState.kt`：宠物状态、经验、成长阶段
- `pet/PetRepository.kt`：本地存档及离线饱腹度衰减
- `ui/PetView.kt`：圆屏绘制、按钮触摸和 10 秒小游戏

## 调试命令

```powershell
adb logcat
adb shell pm list packages | findstr wristpet
adb shell dumpsys package com.example.wristpet
```
