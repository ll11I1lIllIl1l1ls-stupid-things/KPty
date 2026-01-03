# KJPty API Documentation

KJPty is a pseudo-terminal (PTY) interface library for Kotlin/Native, migrated from JPty, allowing you to communicate with processes as if they were running in a real terminal.

## Table of Contents

- [Platform Support](#platform-support)
- [Core API](#core-api)
  - [JPty Object](#jpty-object)
  - [Pty Class](#pty-class)
  - [WinSize Class](#winsize-class)
  - [JPtyException Class](#jptyexception-class)
- [Platform Differences](#platform-differences)
- [Usage Examples](#usage-examples)
- [Error Handling](#error-handling)

---

## Platform Support

| Platform | Target | Runtime Support | Notes |
|----------|--------|----------------|-------|
| Linux x64 | `linuxX64` | Yes | Uses POSIX PTY API |
| Linux ARM64 | `linuxArm64` | Yes | Uses POSIX PTY API |
| macOS x64 | `macosX64` | Yes | Uses POSIX PTY API |
| macOS ARM64 | `macosArm64` | Yes | Uses POSIX PTY API |
| Windows x64 | `mingwX64` | Yes | Uses ConPTY |

> [!IMPORTANT]
> The Windows platform uses ConPTY implementation, requiring Windows 10 1809+ (or Windows Server 2019+).

---

## Core API

### JPty Object

A singleton object that provides PTY-related static methods and constants.

#### Constants

##### `SIGKILL: Int`

Signal constant for terminating processes.

Platform Differences:
- Unix/Linux/macOS: Retrieved from `platform.posix.SIGKILL` (typically 9)
- Windows: Hard-coded as 9

Example:
```kotlin
JPty.signal(pid, JPty.SIGKILL)  // Send SIGKILL signal
```

##### `WNOHANG: Int`

Non-blocking option constant for the `waitpid` function.

Platform Differences:
- Unix/Linux/macOS: Retrieved from `platform.posix.WNOHANG` (typically 1)
- Windows: Hard-coded as 1

---

#### Methods

##### `execInPTY()`

Execute a command in a pseudo-terminal. This is the only public method of the JPty object.

```kotlin
fun execInPTY(
    command: String,
    arguments: List<String> = emptyList(),
    environment: Map<String, String>? = null,
    workingDirectory: String? = null,
    winSize: WinSize? = null
): Pty
```

Parameters:
- `command`: Path to the command to execute (e.g., `/bin/sh`)
- `arguments`: List of command arguments (optional, defaults to empty list)
- `environment`: Environment variable mapping (optional, defaults to null which inherits parent process environment)
- `workingDirectory`: Initial working directory for the child process (optional, defaults to null which inherits parent process working directory)
- `winSize`: Terminal window size (optional, defaults to null)

Returns:
- `Pty` instance for communicating with the child process

Throws:
- `JPtyException`: When PTY creation fails

Platform Differences:
- Unix/Linux/macOS: Uses `forkpty()` system call to create PTY, uses `chdir()` in child process to change working directory
- Windows: Uses ConPTY (`CreatePseudoConsole` + `CreateProcessW`), sets working directory via `lpCurrentDirectory` parameter

Implementation Notes (Unix/Linux/macOS):
- Automatically adds `command` to the beginning of the argument list (if not already present)
- If `workingDirectory` is specified, calls `chdir()` before executing `execve()`
- Uses `execve()` to replace the process image in the child process
- Sets the master PTY file descriptor to blocking mode
- Requires linking with `-lutil` library

Example:
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

### Pty Class

Represents a pseudo-terminal instance for communicating with a child process.

> [!CAUTION]
> Do not instantiate this class directly. Use `JPty.execInPTY()` to create `Pty` instances.

#### Methods

##### `read()`

Read data from the PTY.

```kotlin
fun read(
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size - offset
): Int
```

Parameters:
- `buffer`: Byte array for storing the read data
- `offset`: Starting offset in the buffer (defaults to 0)
- `length`: Maximum number of bytes to read (defaults to from offset to end of buffer)

Returns:
- Number of bytes read, or -1 if EOF is reached

Throws:
- `JPtyException`: When read operation fails
- `IllegalStateException`: When PTY is already closed
- `IllegalArgumentException`: When offset or length is invalid

Platform Differences:
- Unix/Linux/macOS: Uses `read()` system call
- Windows: Uses `ReadFile()` to read from ConPTY output pipe

Example:
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

Write data to the PTY.

```kotlin
fun write(
    buffer: ByteArray,
    offset: Int = 0,
    length: Int = buffer.size - offset
): Int
```

Parameters:
- `buffer`: Byte array containing the data to write
- `offset`: Starting offset in the buffer (defaults to 0)
- `length`: Number of bytes to write (defaults to from offset to end of buffer)

Returns:
- Number of bytes written

Throws:
- `JPtyException`: When write operation fails
- `IllegalStateException`: When PTY is already closed
- `IllegalArgumentException`: When offset or length is invalid

Platform Differences:
- Unix/Linux/macOS: Uses `write()` system call, loops until all data is written
- Windows: Uses `WriteFile()` to write to ConPTY input pipe

Implementation Notes (Unix/Linux/macOS):
- The method loops until all `length` bytes are written
- Guarantees return value equals `length` (unless an exception is thrown)

Example:
```kotlin
pty.write("ls -la\n".encodeToByteArray())
```

---

##### `close()`

Close the PTY and optionally terminate the child process.

```kotlin
fun close(terminateChild: Boolean = true)
```

Parameters:
- `terminateChild`: Whether to terminate the child process (defaults to `true`)

Platform Differences:
- Unix/Linux/macOS:
  - Sets the master PTY to non-blocking mode
  - If `terminateChild` is true and the child process is still running, sends SIGKILL signal
  - Closes the master PTY file descriptor
- Windows:
  - Optionally calls `TerminateProcess`
  - Closes ConPTY and related pipe handles

Example:
```kotlin
pty.close(terminateChild = true)  // Close and terminate child process
```

---

##### `isAlive()`

Check if the child process is still running.

```kotlin
fun isAlive(): Boolean
```

Returns:
- `true` if the child process is alive, `false` otherwise

Platform Differences:
- Unix/Linux/macOS: Delegates to `JPty.isProcessAlive()`
- Windows: Delegates to `JPty.isProcessAlive()`

---

##### `waitFor()`

Wait for the child process to exit and return its exit status.

```kotlin
fun waitFor(): Int
```

Returns:
- Exit status code of the child process, or -1 if PTY is already closed

Platform Differences:
- Unix/Linux/macOS: Uses `waitpid()` to block and wait for child process exit
- Windows: Uses `WaitForSingleObject` to block and wait for child process exit

Example:
```kotlin
val exitCode = pty.waitFor()
println("Process exited with code: $exitCode")
```

---

##### `interrupt()`

Attempt to gracefully interrupt the child process.

Returns:
- Whether the interrupt request was successfully sent

Platform Differences:
- Unix/Linux/macOS: Sends `SIGINT`
- Windows: Sends `CTRL_C_EVENT` (requires process group support)

---

##### `destroy()`

Forcibly terminate the child process.

Returns:
- Whether the termination request was successfully sent

Platform Differences:
- Unix/Linux/macOS: Sends `SIGKILL`
- Windows: Calls `TerminateProcess`

---

##### `getWinSize()`

Get the PTY's window size.

```kotlin
fun getWinSize(): WinSize
```

Returns:
- `WinSize` object containing the current window size

Throws:
- `JPtyException`: When getting window size fails

Platform Differences:
- Unix/Linux/macOS: Uses `JPty.getWinSize()` with error handling wrapper
- Windows: Returns cached window size (most recently set value)

---

##### `setWinSize()`

Set the PTY's window size.

```kotlin
fun setWinSize(winSize: WinSize)
```

Parameters:
- `winSize`: `WinSize` object containing the new window size

Throws:
- `JPtyException`: When setting window size fails

Platform Differences:
- Unix/Linux/macOS: Uses `JPty.setWinSize()` with error handling wrapper
- Windows: Uses `ResizePseudoConsole` to resize ConPTY

Example:
```kotlin
val newSize = WinSize(columns = 80, rows = 24)
pty.setWinSize(newSize)
```

---

### WinSize Class

A data class representing terminal window size.

```kotlin
class WinSize(
    var columns: Int = 0,
    var rows: Int = 0,
    var width: Int = 0,
    var height: Int = 0
)
```

#### Properties

| Property | Type | Description |
|----------|------|-------------|
| `columns` | `Int` | Number of columns (characters) |
| `rows` | `Int` | Number of rows (characters) |
| `width` | `Int` | Width (pixels) |
| `height` | `Int` | Height (pixels) |

> [!NOTE]
> `width` and `height` are typically not used; most applications only need to set `columns` and `rows`.

#### Methods

The following are conversion methods for internal use:

- `toShortRows(): Short` - Convert rows to Short
- `toShortCols(): Short` - Convert columns to Short
- `toShortWidth(): Short` - Convert width to Short
- `toShortHeight(): Short` - Convert height to Short

Example:
```kotlin
val winSize = WinSize(
    columns = 120,
    rows = 30,
    width = 0,
    height = 0
)
```

---

### JPtyException Class

Exception thrown when PTY operations fail.

```kotlin
class JPtyException(message: String, val errno: Int) : RuntimeException(message)
```

#### Properties

##### `errno: Int`

Error code from the system call.

Read-only property

Example:
```kotlin
try {
    val pty = JPty.execInPTY("/nonexistent")
} catch (e: JPtyException) {
    println("PTY error: ${e.message}, errno: ${e.errno}")
}
```

---

## Platform Differences

### Unix/Linux/macOS Implementation

All features are fully supported, using the following system calls:

| Feature | System Call | Description |
|---------|------------|-------------|
| Create PTY | `forkpty()` | Create child process and attach PTY |
| Execute Command | `execve()` | Replace child process image |
| Read Data | `read()` | Read from master PTY |
| Write Data | `write()` | Write to master PTY |
| Window Size | `ioctl(TIOCGWINSZ/TIOCSWINSZ)` | Get/set terminal window size |
| Process Signal | `kill()` | Send signal to process |
| Wait for Process | `waitpid()` | Wait for process state change |
| File Control | `fcntl()` | Set file descriptor flags |

#### Linking Requirements

Linux and macOS platforms require linking with the `libutil` library:

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

### Windows Implementation

Windows (mingwX64) uses ConPTY to implement PTY:

- Creates input/output pipes and calls `CreatePseudoConsole`
- Launches child process using `STARTUPINFOEXW` + `PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE`
- Interacts with child process via `ReadFile`/`WriteFile`
- Resizes window via `ResizePseudoConsole`

#### Notes

1. System Requirements: Requires Windows 10 1809+ or Windows Server 2019+
2. Signal Semantics: Only `SIGKILL` is supported, mapped to `TerminateProcess`
3. Window Size: `getWinSize()` returns the most recently set value (cached)

---

## Usage Examples

### Basic Usage

```kotlin
import jpty.JPty
import jpty.WinSize

fun main() {
    // Create PTY and execute command
    val pty = JPty.execInPTY(
        command = "/bin/sh",
        arguments = listOf("-l"),
        environment = mapOf("TERM" to "xterm"),
        winSize = WinSize(columns = 120, rows = 30)
    )
    
    // Read initial output
    val buffer = ByteArray(1024)
    val bytesRead = pty.read(buffer)
    println(buffer.decodeToString(0, bytesRead))
    
    // Send command
    pty.write("echo 'Hello, PTY!'\n".encodeToByteArray())
    
    // Read response
    val response = pty.read(buffer)
    println(buffer.decodeToString(0, response))
    
    // Wait for process to exit
    val exitCode = pty.waitFor()
    println("Exit code: $exitCode")
    
    // Close PTY
    pty.close()
}
```

### Interactive Shell

```kotlin
import jpty.JPty
import jpty.WinSize
import kotlin.concurrent.thread

fun interactiveShell() {
    val pty = JPty.execInPTY(
        command = "/bin/bash",
        winSize = WinSize(columns = 80, rows = 24)
    )
    
    // Launch read thread
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
    
    // Main thread handles input
    val stdin = System.`in`.bufferedReader()
    while (pty.isAlive()) {
        val line = stdin.readLine() ?: break
        pty.write("$line\n".encodeToByteArray())
    }
    
    pty.close()
}
```

### Dynamic Window Resizing

```kotlin
import jpty.JPty
import jpty.WinSize

fun resizeDemo() {
    val pty = JPty.execInPTY("/bin/bash")
    
    // Get current window size
    val currentSize = pty.getWinSize()
    println("Current size: ${currentSize.columns}x${currentSize.rows}")
    
    // Resize window
    val newSize = WinSize(columns = 100, rows = 40)
    pty.setWinSize(newSize)
    println("Resized to: ${newSize.columns}x${newSize.rows}")
    
    // Verify new size
    val verifySize = pty.getWinSize()
    println("Verified size: ${verifySize.columns}x${verifySize.rows}")
    
    pty.close()
}
```

### Process Management

```kotlin
import jpty.JPty

fun processManagement() {
    val pty = JPty.execInPTY("/usr/bin/sleep", listOf("10"))
    
    println("Is alive: ${pty.isAlive()}")
    
    // Forcibly terminate process
    pty.destroy()
    
    // Wait for process to exit
    val status = pty.waitFor()
    println("Exit status: $status")
    println("Is alive: ${pty.isAlive()}")
    
    pty.close(terminateChild = false)  // Already terminated, no need to terminate again
}
```

### Platform Detection

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

## Error Handling

### Common Error Scenarios

#### 1. PTY Creation Failure

```kotlin
try {
    val pty = JPty.execInPTY("/nonexistent/command")
} catch (e: JPtyException) {
    println("Failed to create PTY: ${e.message}")
    println("Error code: ${e.errno}")
}
```

#### 2. Read/Write Operation Failure

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

#### 3. Window Size Setting Failure

```kotlin
val pty = JPty.execInPTY("/bin/sh")
try {
    pty.setWinSize(WinSize(columns = -1, rows = -1))  // Invalid values
} catch (e: JPtyException) {
    println("Failed to set window size: ${e.message}")
}
```

#### 4. Platform System Requirements Not Met

```kotlin
try {
    val pty = JPty.execInPTY("/bin/sh")
} catch (e: JPtyException) {
    println("PTY creation failed: ${e.message}")
    println("Check ConPTY requirements on Windows (10 1809+ / Server 2019+).")
}
```

---

### Best Practices

1. Always Close PTY: Use `try-finally` or `use` pattern to ensure resource cleanup

```kotlin
val pty = JPty.execInPTY("/bin/sh")
try {
    // Use pty
} finally {
    pty.close()
}
```

2. Check Process Status: Check if child process is alive before operations

```kotlin
if (pty.isAlive()) {
    pty.write(command.encodeToByteArray())
}
```

3. Handle EOF: Read operation returns -1 to indicate end of file

```kotlin
val n = pty.read(buffer)
if (n == -1) {
    println("Reached end of stream")
}
```

4. Use Reasonable Buffer Size: Recommend using 4KB or larger buffers

```kotlin
val buffer = ByteArray(4096)  // Recommended
```

5. Platform Detection: In multiplatform projects, use `expect`/`actual` or runtime detection

```kotlin
expect fun isPtySupported(): Boolean

// Unix/Linux/macOS
actual fun isPtySupported(): Boolean = true

// Windows (ConPTY required)
actual fun isPtySupported(): Boolean = true
```