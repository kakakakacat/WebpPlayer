# WebpKit Player

[English](README.md) | 简体中文

一个**独立、零宿主依赖**的 Android 动画 WebP 播放库，底层内置了一份
[libwebp](https://chromium.googlesource.com/webm/libwebp) 源码（编码 + 解码），
通过 CMake 从源码现编译出原生 `.so`（无预编译二进制）。

它通过 JNI 把动画 WebP 的每一帧解码为 RGBA，然后用两种方式渲染：OpenGL ES 2.0
表面（开销最低、GPU 合成），或平台自带的 `ImageDecoder` / `AnimatedImageDrawable`
流水线。

- **零宿主依赖** —— 自带日志、线程调度和 Context 引导。AAR 丢进去即用，无需调用任何初始化方法。
- **原生 libwebp** —— 完整的编/解码 C 源码原样内置并从零编译，当前打包架构为 `arm64-v8a`。
- **帧缓存** —— 带设备自适应的内存预算和异步回填（refill）。

## 工作原理 & 与原生 WebP / Glide 的区别

本库采用**一次性全量解码 + GPU 合成**：用原生 libwebp（JNI）把 WebP 的**每一帧
一次性**解码为 RGBA、存进 native 内存缓冲，之后播放就是纯粹的 OpenGL ES 纹理切换。
播放阶段几乎不占主线程 / CPU（由 GPU 合成），且解码后的帧会被缓存（读取时 clone、
LRU 淘汰、设备自适应预算）并在多个 View 间复用。

这与系统的 `AnimatedImageDrawable` 或 Glide 是完全不同的取舍——后两者是
**每一轮循环都在 CPU 上按需解码**、再通过 View 的 canvas 绘制：

| | **WebpKit（本库）** | **系统 `ImageDecoder` / `AnimatedImageDrawable`** | **Glide（动画 WebP）** |
|---|---|---|---|
| 解码模型 | 所有帧**一次性**预解码（原生） | **按需**解码，每轮循环都解 | 按需（API 28+ 委托给系统解码器） |
| 播放开销 | GPU 纹理切换，**几乎无逐帧 CPU** | 每轮循环都做逐帧 CPU 解码 | 逐帧 CPU 解码 + Glide 流水线 |
| 多个动画并存 | **单个** GL surface，一个绘制循环画所有图层 | N 个 View → N 套 invalidate/onDraw | N 个 Drawable → N 套 invalidate |
| 合成方式 | **GPU** | View 的 CPU / 硬件 canvas | View 的 CPU / 硬件 canvas |
| 内存 | **较高** —— 所有帧常驻 | 较低 —— 只保留当前帧 | 较低 + Glide 缓存 |
| 网络 / 磁盘加载 | 不内置（走 raw 资源） | 无 | **有** —— Glide 的强项 |
| RecyclerView / 列表 | **不适合**（GL surface + z-order） | 适合 | **最佳** —— 专为此设计 |
| 在动画上盖 `View` | 需要 `MultiWebpGLViewContainer`（见 [Z-order](#z-order在动画之上叠加-view)） | 轻松 | 轻松 |

**适合用 WebpKit 的场景**：在相对静态的页面上播放少量高帧率 / 多图层动画（开屏、
全屏特效、角色 / 贴纸合成），追求极顺滑的播放和极低的 CPU 占用。**列表 / 可滚动内容、
远程加载的图片、或需要随意在其上叠加视图**时，请优先用系统解码器或 Glide。

### 性能优势

- **播放期无逐帧 CPU 解码** —— 帧已预解码，每显示一帧只是一次 `glTexSubImage2D` +
  绘制，即使高帧率、多图层也依然顺滑。
- **解码一次，处处复用** —— `WebPAnimResultManager` 缓存 native 帧（读取时 clone、
  LRU、按设备的内存预算）；同一资源在多个 View 显示也只解码一次。
- **多图层共用一个 surface** —— `MultiWebpGLView` 在一个 GL 上下文 / 一个帧循环里
  合成 N 个动画，而不是 N 个动画 View 各自触发 invalidate → onDraw。
- **解码在非主线程**，并显式释放 native 内存。

### 取舍（请务必了解）

- **内存** —— 每一帧都解码并常驻，所以长 / 大的 WebP 比按需解码更吃 RAM。可传
  `size` / `decodeSize` 在解码时降采样。
- **Z-order** —— GL 系列 View 使用了 `setZOrderOnTop(true)`，surface 会被画在
  **所有兄弟 View 之上**，无法直接在它上面盖普通 `View`。解决办法见
  [Z-order](#z-order在动画之上叠加-view)。
- **不适合 RecyclerView** —— surface 生命周期 / 资源冲突会导致闪烁。

## 公开 API

| 类 | 作用 |
|----|------|
| `WebpGLView` | 一个 `GLSurfaceView`，从 raw 资源解码并播放**单个**动画 WebP。 |
| `WebpImageView` | 一个 `ImageView`，通过 `AnimatedImageDrawable` 播放动画 WebP（支持同步/异步解码）。 |
| `MultiWebpGLView` | 在**一个** GL 表面上渲染**多个** WebP 图层，每层有独立的位置、尺寸和帧率。 |
| `MultiWebpGLViewContainer` | 包装 `MultiWebpGLView`，可把单个图层从 GL 表面"抬"出来，让别的 View 盖在它上面。 |
| `WebPAnimResultManager` | 解码缓存（读取时 clone、native 内存承载、LRU 淘汰）。 |
| `WebPYUVDecoder` | 底层 JNI 解码器（`decodeAllFrames`、`decodeAllFramesWithSize`、缓冲区 clone/release）。 |

## 环境要求

- `minSdk` 28（用到 `ImageDecoder` / `AnimatedImageDrawable`）
- ABI：`arm64-v8a`
- NDK + CMake 仅在**从源码构建**时需要——直接用 AAR 的使用者无需安装。

## 接入

### 方式 A —— Gradle 依赖（推荐，自动下载）

加一个仓库地址，再加一行依赖即可。传递依赖（coroutines、lifecycle）会通过发布的 POM
自动带入，无需手动声明。

`settings.gradle.kts`（或工程级 `build.gradle`）：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://raw.githubusercontent.com/kakakakacat/WebpPlayer/mvn-repo/") }
    }
}
```

App 的 `build.gradle.kts`：

```kotlin
dependencies {
    implementation("io.webpkit:player:1.0.1")
}
```

<details>
<summary>Groovy DSL 写法</summary>

```groovy
// settings.gradle
maven { url 'https://raw.githubusercontent.com/kakakakacat/WebpPlayer/mvn-repo/' }
// build.gradle
implementation 'io.webpkit:player:1.0.1'
```
</details>

### 方式 B —— 手动丢 AAR

把 `player-1.0.1.aar` 拷进 App 的 `libs/` 目录，然后：

```kotlin
dependencies {
    implementation(files("libs/player-1.0.1.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
}
```

### 方式 C —— 从源码构建

```bash
git clone https://github.com/kakakakacat/WebpPlayer.git
cd WebpPlayer
./gradlew :webpview:assembleRelease
# 产物 → webpview/build/outputs/aar/webpview-release.aar
```

> `./gradlew :webpview:assembleRelease` 这一条命令会**自动**从内置的 libwebp 源码
> 编译出 `.so` 并打进 AAR，无需单独编译。如果你想单独构建原生库，也可以运行根目录的
> `./compile.sh`（产物输出到 `dist/arm64-v8a/`）。

## 使用示例

### GL 表面播放单个动画

```kotlin
val view = WebpGLView(context)
view.setWebpFromRaw(R.raw.my_anim, fps = 20)
view.start()   // 播放
view.stop()    // 暂停
```

### 用 ImageView 简单播放

```kotlin
val iv = WebpImageView(context)
iv.setWebpFromRaw(R.raw.my_anim)
iv.start()
```

### 多图层

```kotlin
val gl = MultiWebpGLView(context)
gl.setLayers(
    listOf(
        WebpLayer(R.raw.bg, x = 0f,  y = 0f,  width = 300f, height = 300f, fps = 24),
        WebpLayer(R.raw.fx, x = 80f, y = 80f, width = 140f, height = 140f, fps = 12),
    )
)
gl.start()
gl.setLayerPosition(1, 100f, 100f)   // 不重新解码即可移动图层
gl.hideLayer(1)                       // 切换可见性
```

### Z-order：在动画之上叠加 View

由于 GL 系列 View 使用了 `setZOrderOnTop(true)`，动画 surface 会被画在**所有兄弟
View 之上**——把普通 `View` 作为兄弟节点加进去，并*不会*盖在动画上面。当你需要在
某个动画上叠加普通 `View`（角标、按钮、文字、倒计时……）时，请用
**`MultiWebpGLViewContainer`**：它会把**单个**图层从 GL surface 上「抬」下来，用一个
位置 / 尺寸完全一致的 `WebpImageView` 放进正常 View 层级里（原图层处的 GL surface
变透明），这样覆盖视图就又能生效了——同时其余图层继续在 GPU 上正常播放、不受影响。

```kotlin
val container = MultiWebpGLViewContainer(context)
container.glView.setLayers(
    listOf(
        WebpLayer(R.raw.bg,    x = 0f,  y = 0f,  width = 300f, height = 300f, fps = 24),
        WebpLayer(R.raw.badge, x = 80f, y = 80f, width = 140f, height = 140f, fps = 12),
    )
)
container.glView.start()

// 把第 1 层从 GL surface 抬下来，这样普通 View 才能盖在它上面。
// onFrozen 会在交接无缝完成后回调（覆盖层已绘制、GL 图层已隐藏）。
container.freezeLayer(1, onFrozen = {
    val label = TextView(context).apply { text = "NEW" }
    container.addView(
        label,
        FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            leftMargin = 80; topMargin = 80   // 正好盖在（已冻结的）图层上方
        },
    )
})

// 不再需要覆盖层时，把图层交还给 GPU surface。
container.unfreezeLayer(1)
```

`freezeLayer` / `unfreezeLayer` 是无缝的（覆盖层只在真正绘制出一帧后才换入 / 换出），
可重复调用；如需延迟交换还可传 `delayMs`。

> ⚠️ 即便有了容器，也**仍然**不要把任何 GL 系列 View 放进 `RecyclerView`
> （表面生命周期 / 资源冲突会导致黑屏或闪烁）。

## 初始化

应用 Context 会在进程启动时由一个极小的 `ContentProvider`（`WebpInitProvider`）
自动捕获，使用者无需任何操作。如果你裁剪了清单里的 provider，请手动调用一次：

```kotlin
WebpContext.init(applicationContext)
```

关闭日志（例如 Release 包）：

```kotlin
WebpLog.enabled = false
```

## 架构说明

- `webpview/src/main/cpp/libwebp/` 是 libwebp 的**逐字节原样拷贝**，未改动任何源文件。
- 唯一改动的原生文件是 JNI 胶水 `WebPYUVDecoder.cpp`，仅把函数名与 `FindClass` 的包名
  由原始包改为 `io.webpkit.player`（JNI 按包名逐字绑定，重命名后这是必须的）。
- 其余适配（日志、线程、Context、视图基类）全部集中在上层 Kotlin 代码。

## 许可证

封装代码采用 MIT 许可。内置的 libwebp 由 Google 以 BSD 许可证发布——详见
[`LICENSE`](LICENSE) 与 `webpview/src/main/cpp/libwebp/COPYING`。
