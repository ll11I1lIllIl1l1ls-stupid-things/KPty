# KJPty API 文档

KJPty 是一个自 JPty 迁移，用于 Kotlin/Native 的伪终端 (PTY) 接口库，允许您与进程进行通信，就像它们在真实终端中运行一样。

## 目录

- [平台支持](#平台支持)
- [核心 API](#核心-api)
  - [JPty 对象](#jpty-对象)
  - [Pty 类](#pty-类)
  - [WinSize 类](#winsize-类)
  - [JPtyException 类](#jptyexception-类)
- [平台差异](#平台差异)
- [使用示例](#使用示例)
- [错误处理](#错误处理)

---

## 平台支持

| 平台 | 目标 | 运行时支持 | 说明 |
|------|------|-----------|------|
| Linux x64 | `linuxX64` | ✅ 完全支持 | 使用 POSIX PTY API |
| Linux ARM64 | `linuxArm64` | ✅ 完全支持 | 使用 POSIX PTY API |
| macOS x64 | `macosX64` | ✅ 完全支持 | 使用 POSIX PTY API |
| macOS ARM64 | `macosArm64` | ✅ 完全支持 | 使用 POSIX PTY API |
| Windows x64 | `mingwX64` | ✅ 支持 | 使用 ConPTY（Windows 10 1809+ / Server 2019+） |

> [!IMPORTANT]
> Windows 平台使用 ConPTY 实现，要求 Windows 10 1809+（或 Windows Server 2019+）。

---

## 核心 API

### JPty 对象

单例对象，提供 PTY 相关的静态方法和常量。

#### 常量

##### `SIGKILL: Int`

终止进程的信号常量。

**平台差异：**
- **Unix/Linux/macOS**: 从 `platform.posix.SIGKILL` 获取（通常为 9）
- **Windows**: 硬编码为 9

**示例：**
```kotlin
JPty.signal(pid, JPty.SIGKILL)  // 发送 SIGKILL 信号
```

##### `WNOHANG: Int`

`waitpid` 函数的非阻塞选项常量。

**平台差异：**
- **Unix/Linux/macOS**: 从 `platform.posix.WNOHANG` 获取（通常为 1）
- **Windows**: 硬编码为 1

---

#### 方法

##### `execInPTY()`

在伪终端中执行命令。这是 JPty 对象唯一的公共方法。

```kotlin
fun execInPTY(
    command: String,
    arguments: List<String> = emptyList(),
    environment: Map<String, String>? = null,
    workingDirectory: String? = null,
    winSize: WinSize? = null
): Pty
```

**参数：**
- `command`: 要执行的命令路径（例如 `/bin/sh`）
- `arguments`: 命令参数列表（可选，默认为空列表）
- `environment`: 环境变量映射（可选，默认为 null 表示继承父进程环境）
- `workingDirectory`: 子进程的初始工作目录（可选，默认为 null 表示继承父进程工作目录）
- `winSize`: 终端窗口大小（可选，默认为 null）

**返回：**
- `Pty` 实例，用于与子进程通信

**异常：**
- `JPtyException`: 当 PTY 创建失败时抛出

**平台差异：**
- **Unix/Linux/macOS**: 使用 `forkpty()` 系统调用创建 PTY，在子进程中使用 `chdir()` 切换工作目录
- **Windows**: 使用 ConPTY（`CreatePseudoConsole` + `CreateProcessW`），通过 `lpCurrentDirectory` 参数设置工作目录

**实现说明（Unix/Linux/macOS）：**
- 自动将 `command` 添加到参数列表的开头（如果尚未存在）
- 如果指定了 `workingDirectory`，在执行 `execve()` 前调用 `chdir()` 切换目录
- 使用 `execve()` 在子进程中替换进程映像
- 设置主 PTY 文件描述符为阻塞模式
- 需要链接 `-lutil` 库

**示例：**
```kotlin
val pty = JPty.execInPTY(
    command = "/bin/bash",
    arguments = listOf("-l"),
    environment = mapOf("TERM" to "xterm-256color"),
    workingDirectory = "/home/user/projects",
    winSize = WinSize(columns = 120, rows = 30)
)
```

---

### Pty 类

表示一个伪终端实例，用于与子进程通信。

> [!CAUTION]
> 不要直接实例化此类，请使用 `JPty.execInPTY()` 创建 `Pty` 实例。

#### 方法

##### `read()`

从 PTY 读取数据。

```kotlin
fun read(
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size - offset
): Int
```

**参数：**
- `buffer`: 用于存储读取数据的字节数组
- `offset`: 缓冲区起始偏移量（默认为 0）
- `length`: 要读取的最大字节数（默认为从 offset 到缓冲区末尾）

**返回：**
- 读取的字节数，如果到达 EOF 返回 -1

**异常：**
- `JPtyException`: 读取失败时抛出
- `IllegalStateException`: PTY 已关闭时抛出
- `IllegalArgumentException`: offset 或 length 无效时抛出

**平台差异：**
- **Unix/Linux/macOS**: 使用 `read()` 系统调用
- **Windows**: 使用 `ReadFile()` 从 ConPTY 输出管道读取

**示例：**
```kotlin
val buffer = ByteArray(1024)
val bytesRead = pty.read(buffer)
if (bytesRead > 0) {
    val output = buffer.decodeToString(0, bytesRead)
    println(output)
}
```

---

##### `write()`

向 PTY 写入数据。

```kotlin
fun write(
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size - offset
): Int
```

**参数：**
- `buffer`: 包含要写入数据的字节数组
- `offset`: 缓冲区起始偏移量（默认为 0）
- `length`: 要写入的字节数（默认为从 offset 到缓冲区末尾）

**返回：**
- 写入的字节数

**异常：**
- `JPtyException`: 写入失败时抛出
- `IllegalStateException`: PTY 已关闭时抛出
- `IllegalArgumentException`: offset 或 length 无效时抛出

**平台差异：**
- **Unix/Linux/macOS**: 使用 `write()` 系统调用，循环写入直到所有数据都已写入   
- **Windows**: 使用 `WriteFile()` 向 ConPTY 输入管道写入

**实现说明（Unix/Linux/macOS）：**
- 方法会循环写入，直到所有 `length` 字节都已写入
- 保证返回值等于 `length`（除非抛出异常）

**示例：**
```kotlin
pty.write("ls -la\n".encodeToByteArray())
```

---

##### `close()`

关闭 PTY 并可选地终止子进程。

```kotlin
fun close(terminateChild: Boolean = true)
```

**参数：**
- `terminateChild`: 是否终止子进程（默认为 `true`）

**平台差异：**
- **Unix/Linux/macOS**:
  - 将主 PTY 设置为非阻塞模式
  - 如果 `terminateChild` 为 true 且子进程仍在运行，发送 SIGKILL 信号
  - 关闭主 PTY 文件描述符
- **Windows**:
  - 可选地调用 `TerminateProcess`
  - 关闭 ConPTY 与相关管道句柄

**示例：**
```kotlin
pty.close(terminateChild = true)  // 关闭并终止子进程
```

---

##### `isAlive()`

检查子进程是否仍在运行。

```kotlin
fun isAlive(): Boolean
```

**返回：**
- 子进程存活返回 `true`，否则返回 `false`

**平台差异：**
- **Unix/Linux/macOS**: 委托给 `JPty.isProcessAlive()`
- **Windows**: 委托给 `JPty.isProcessAlive()`

---

##### `waitFor()`

等待子进程退出并返回其退出状态。

```kotlin
fun waitFor(): Int
```

**返回：**
- 子进程的退出状态码，如果 PTY 已关闭返回 -1

**平台差异：**
- **Unix/Linux/macOS**: 使用 `waitpid()` 阻塞等待子进程退出
- **Windows**: 使用 `WaitForSingleObject` 阻塞等待子进程退出

**示例：**
```kotlin
val exitCode = pty.waitFor()
println("Process exited with code: $exitCode")
```

---

##### `interrupt()`

尝试优雅中断子进程。

**返回：**
- 中断请求是否成功发送

**平台差异：**
- **Unix/Linux/macOS**: 发送 `SIGINT`
- **Windows**: 发送 `CTRL_C_EVENT`（需要进程组支持）

---

##### `destroy()`

强制终止子进程。

**返回：**
- 终止请求是否成功发送

**平台差异：**
- **Unix/Linux/macOS**: 发送 `SIGKILL`
- **Windows**: 调用 `TerminateProcess`

---

##### `getWinSize()`

获取 PTY 的窗口大小。

```kotlin
fun getWinSize(): WinSize
```

**返回：**
- 包含当前窗口大小的 `WinSize` 对象

**异常：**
- `JPtyException`: 获取窗口大小失败时抛出

**平台差异：**
- **Unix/Linux/macOS**: 使用 `JPty.getWinSize()` 并包装错误处理
- **Windows**: 返回缓存的窗口大小（最近一次设置值）

---

##### `setWinSize()`

设置 PTY 的窗口大小。

```kotlin
fun setWinSize(winSize: WinSize)
```

**参数：**
- `winSize`: 包含新窗口大小的 `WinSize` 对象

**异常：**
- `JPtyException`: 设置窗口大小失败时抛出

**平台差异：**
- **Unix/Linux/macOS**: 使用 `JPty.setWinSize()` 并包装错误处理
- **Windows**: 使用 `ResizePseudoConsole` 调整 ConPTY 大小

**示例：**
```kotlin
val newSize = WinSize(columns = 80, rows = 24)
pty.setWinSize(newSize)
```

---

### WinSize 类

表示终端窗口大小的数据类。

```kotlin
class WinSize(
    var columns: Int = 0,
    var rows: Int = 0,
    var width: Int = 0,
    var height: Int = 0
)
```

#### 属性

| 属性 | 类型 | 说明 |
|------|------|------|
| `columns` | `Int` | 列数（字符数） |
| `rows` | `Int` | 行数（字符数） |
| `width` | `Int` | 宽度（像素） |
| `height` | `Int` | 高度（像素） |

> [!NOTE]
> `width` 和 `height` 通常不被使用，大多数应用程序只需要设置 `columns` 和 `rows`。

#### 方法

以下是内部使用的转换方法：

- `toShortRows(): Short` - 将行数转换为 Short
- `toShortCols(): Short` - 将列数转换为 Short
- `toShortWidth(): Short` - 将宽度转换为 Short
- `toShortHeight(): Short` - 将高度转换为 Short

**示例：**
```kotlin
val winSize = WinSize(
    columns = 120,
    rows = 30,
    width = 0,
    height = 0
)
```

---

### JPtyException 类

PTY 操作失败时抛出的异常。

```kotlin
class JPtyException(message: String, val errno: Int) : RuntimeException(message)
```

#### 属性

##### `errno: Int`

系统调用的错误码。

**只读属性**

**示例：**
```kotlin
try {
    val pty = JPty.execInPTY("/nonexistent")
} catch (e: JPtyException) {
    println("PTY error: ${e.message}, errno: ${e.errno}")
}
```

---

## 平台差异

### Unix/Linux/macOS 实现

所有功能均完全支持，使用以下系统调用：

| 功能 | 系统调用 | 说明 |
|------|---------|------|
| 创建 PTY | `forkpty()` | 创建子进程并附加 PTY |
| 执行命令 | `execve()` | 替换子进程映像 |
| 读取数据 | `read()` | 从主 PTY 读取 |
| 写入数据 | `write()` | 向主 PTY 写入 |
| 窗口大小 | `ioctl(TIOCGWINSZ/TIOCSWINSZ)` | 获取/设置终端窗口大小 |
| 进程信号 | `kill()` | 发送信号给进程 |
| 等待进程 | `waitpid()` | 等待进程状态改变 |
| 文件控制 | `fcntl()` | 设置文件描述符标志 |

#### 链接要求

Linux 和 macOS 平台需要链接 `libutil` 库：

```kotlin
// build.gradle.kts
kotlin.targets
    .withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
    .configureEach {
        binaries.all {
            if (konanTarget.family == Family.LINUX || konanTarget.family == Family.OSX) {
                linkerOpts("-lutil")
            }
        }
    }
```

### Windows 实现

Windows (mingwX64) 使用 ConPTY 实现 PTY：

- 创建输入/输出管道并调用 `CreatePseudoConsole`
- 使用 `STARTUPINFOEXW` + `PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE` 启动子进程
- 通过 `ReadFile`/`WriteFile` 与子进程交互
- 通过 `ResizePseudoConsole` 调整窗口大小

#### 注意事项

1. **系统要求**: 需要 Windows 10 1809+ 或 Windows Server 2019+
2. **信号语义**: 仅支持 `SIGKILL`，映射为 `TerminateProcess`
3. **窗口大小**: `getWinSize()` 返回最近一次设置值（缓存）

---

## 使用示例

### 基本用法

```kotlin
import jpty.JPty
import jpty.WinSize

fun main() {
    // 创建 PTY 并执行命令
    val pty = JPty.execInPTY(
        command = "/bin/sh",
        arguments = listOf("-l"),
        environment = mapOf("TERM" to "xterm"),
        winSize = WinSize(columns = 120, rows = 30)
    )
    
    // 读取初始输出
    val buffer = ByteArray(1024)
    val bytesRead = pty.read(buffer)
    println(buffer.decodeToString(0, bytesRead))
    
    // 发送命令
    pty.write("echo 'Hello, PTY!'\n".encodeToByteArray())
    
    // 读取响应
    val response = pty.read(buffer)
    println(buffer.decodeToString(0, response))
    
    // 等待进程退出
    val exitCode = pty.waitFor()
    println("Exit code: $exitCode")
    
    // 关闭 PTY
    pty.close()
}
```

### 交互式 Shell

```kotlin
import jpty.JPty
import jpty.WinSize
import kotlin.concurrent.thread

fun interactiveShell() {
    val pty = JPty.execInPTY(
        command = "/bin/bash",
        winSize = WinSize(columns = 80, rows = 24)
    )
    
    // 启动读取线程
    thread {
        val buffer = ByteArray(4096)
        while (pty.isAlive()) {
            try {
                val n = pty.read(buffer)
                if (n > 0) {
                    print(buffer.decodeToString(0, n))
                }
            } catch (e: Exception) {
                break
            }
        }
    }
    
    // 主线程处理输入
    val stdin = System.`in`.bufferedReader()
    while (pty.isAlive()) {
        val line = stdin.readLine() ?: break
        pty.write("$line\n".encodeToByteArray())
    }
    
    pty.close()
}
```

### 动态调整窗口大小

```kotlin
import jpty.JPty
import jpty.WinSize

fun resizeDemo() {
    val pty = JPty.execInPTY("/bin/bash")
    
    // 获取当前窗口大小
    val currentSize = pty.getWinSize()
    println("Current size: ${currentSize.columns}x${currentSize.rows}")
    
    // 调整窗口大小
    val newSize = WinSize(columns = 100, rows = 40)
    pty.setWinSize(newSize)
    println("Resized to: ${newSize.columns}x${newSize.rows}")
    
    // 验证新大小
    val verifySize = pty.getWinSize()
    println("Verified size: ${verifySize.columns}x${verifySize.rows}")
    
    pty.close()
}
```

### 进程管理

```kotlin
import jpty.JPty

fun processManagement() {
    val pty = JPty.execInPTY("/usr/bin/sleep", listOf("10"))
    
    println("Is alive: ${pty.isAlive()}")
    
    // 强制终止进程
    pty.destroy()
    
    // 等待进程退出
    val status = pty.waitFor()
    println("Exit status: $status")
    println("Is alive: ${pty.isAlive()}")
    
    pty.close(terminateChild = false)  // 已经终止，无需再次终止
}
```

### 平台检测

```kotlin
import jpty.JPty

fun platformCheck() {
    try {
        val pty = JPty.execInPTY("/bin/echo", listOf("test"))
        println("PTY is supported on this platform")
        pty.close()
    } catch (e: JPtyException) {
        println("PTY creation failed: ${e.message}")
    }
}
```

---

## 错误处理

### 常见错误场景

#### 1. PTY 创建失败

```kotlin
try {
    val pty = JPty.execInPTY("/nonexistent/command")
} catch (e: JPtyException) {
    println("Failed to create PTY: ${e.message}")
    println("Error code: ${e.errno}")
}
```

#### 2. 读写操作失败

```kotlin
val pty = JPty.execInPTY("/bin/sh")
try {
    val buffer = ByteArray(1024)
    val n = pty.read(buffer)
} catch (e: JPtyException) {
    println("Read failed: ${e.message}")
} catch (e: IllegalStateException) {
    println("PTY is closed")
}
```

#### 3. 窗口大小设置失败

```kotlin
val pty = JPty.execInPTY("/bin/sh")
try {
    pty.setWinSize(WinSize(columns = -1, rows = -1))  // 无效值
} catch (e: JPtyException) {
    println("Failed to set window size: ${e.message}")
}
```

#### 4. 平台不满足系统要求

```kotlin
try {
    val pty = JPty.execInPTY("/bin/sh")
} catch (e: JPtyException) {
    println("PTY creation failed: ${e.message}")
    println("Check ConPTY requirements on Windows (10 1809+ / Server 2019+).")
}
```

---

### 最佳实践

1. **始终关闭 PTY**: 使用 `try-finally` 或 `use` 模式确保资源释放

```kotlin
val pty = JPty.execInPTY("/bin/sh")
try {
    // 使用 pty
} finally {
    pty.close()
}
```

2. **检查进程状态**: 在操作前检查子进程是否存活

```kotlin
if (pty.isAlive()) {
    pty.write(command.encodeToByteArray())
}
```

3. **处理 EOF**: 读取操作返回 -1 表示到达文件末尾

```kotlin
val n = pty.read(buffer)
if (n == -1) {
    println("Reached end of stream")
}
```

4. **使用合理的缓冲区大小**: 建议使用 4KB 或更大的缓冲区

```kotlin
val buffer = ByteArray(4096)  // 推荐
```

5. **平台检测**: 在多平台项目中，使用 `expect`/`actual` 或运行时检测

```kotlin
expect fun isPtySupported(): Boolean

// Unix/Linux/macOS
actual fun isPtySupported(): Boolean = true

// Windows (ConPTY required)
actual fun isPtySupported(): Boolean = true
```

---

## 版本信息

- **当前版本**: 0.2.0
- **Kotlin 版本**: 1.9.22
- **许可证**: Apache Software License 2.0

## 相关资源

- [项目仓库](https://github.com/yourrepo/JPty) (如果有)
- [POSIX PTY 文档](https://man7.org/linux/man-pages/man7/pty.7.html)
- [Kotlin/Native 文档](https://kotlinlang.org/docs/native-overview.html)
