import jpty.JPty
import jpty.WinSize
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.exit
import platform.posix.getenv
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
fun main() {
    val isWindows = Platform.osFamily == OsFamily.WINDOWS

    val systemRoot = if(isWindows) getenv("SystemRoot")?.toKString() ?:"C:\\Windows" else null
    val shell = if (isWindows) "$systemRoot\\system32\\WindowsPowerShell\\v1.0\\powershell.exe" else "/bin/sh"
    val args = if (isWindows) listOf("-NoLogo") else listOf("-l")
    val newline = if (isWindows) "\r\n" else "\n"

    println("Starting PTY with shell: $shell")

    // 创建 PTY 并执行命令
    val pty =
            try {
                JPty.execInPTY(
                        command = shell,
                        arguments = args,
                        environment = if (isWindows) null else mapOf("TERM" to "xterm"),
                        winSize = WinSize(columns = 120, rows = 30),
                    workingDirectory = getenv("HOME")?.toKString() ?: getenv("USERPROFILE")?.toKString()
                )
            } catch (e: UnsupportedOperationException) {
                println("Error: ${e.message}")
                return
            }

    // 读取初始输出
    val buffer = ByteArray(1024)

    // 发送命令
    val cmd = "echo 'Hello, PTY!'$newline"
    println("Writing test command...")
    val bytesWritten = pty.write(cmd.encodeToByteArray())
    println("$bytesWritten written")
    println("Test command written.")

    var found = false
    val maxReads = 10
    for (i in 0 until maxReads) {
        try {
            println("try read")
            val bytesRead = pty.read(buffer)
            println("$bytesRead read")
            if (bytesRead > 0) {
                val output = buffer.decodeToString(0, bytesRead)
                print(output)
                if (output.contains("Hello, PTY!")) {
                    found = true
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    if (found) {
        println("Success: Hello, PTY! was found.")
    } else {
        println("Warning: Did not see echo output.")
    }

    // 发送 exit
    println("Writing exit...")
    pty.write("exit$newline".encodeToByteArray())
    println("Exit written.")

    // 等待进程退出
    val exitCode = pty.waitFor()
    println("Exit code: $exitCode")

    // 关闭 PTY
    pty.close()
    exit(0)
}
