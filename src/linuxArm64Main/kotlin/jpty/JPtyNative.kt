@file:OptIn(ExperimentalForeignApi::class)

package jpty

import jpty.pty.forkpty
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.linux.forkpty
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.SIGINT
import platform.posix.SIGKILL as POSIX_SIGKILL
import platform.posix.TIOCGWINSZ
import platform.posix.TIOCSWINSZ
import platform.posix.WNOHANG as POSIX_WNOHANG
import platform.posix.close
import platform.posix.execve
import platform.posix.fcntl
import platform.posix.ioctl
import platform.posix.kill
import platform.posix.read
import platform.posix.waitpid
import platform.posix.winsize
import platform.posix.write

actual object JPty {
  actual val SIGKILL: Int = POSIX_SIGKILL
  actual val WNOHANG: Int = POSIX_WNOHANG

  actual fun execInPTY(
          command: String,
          arguments: List<String>,
          environment: Map<String, String>?,
          winSize: WinSize?
  ): Pty {
    val argv = processArgv(command, arguments)
    val env = environment?.map { (key, value) -> "$key=$value" } ?: emptyList()

    return memScoped {
      val master = alloc<IntVar>()
      val winp = winSize?.let { allocWinsizePtr(it) }
      val pid = forkpty(master.ptr, null, null, winp)
      if (pid < 0) {
        throw JPtyException("Failed to open PTY!", platform.posix.errno)
      }

      if (pid == 0) {
        if (winSize != null && setWinSizeImpl(0, winSize) < 0) {
          platform.posix.perror("JPty: setWinSize failed")
          platform.posix._exit(127)
        }

        execve(command, cStringArray(argv), cStringArray(env))
        platform.posix.perror("JPty: execve failed")
        platform.posix._exit(127)
      }

      val masterFd = master.value
      if (masterFd < 0) {
        throw JPtyException("Failed to fork PTY!", -1)
      }

      if (fcntl(masterFd, F_SETFL, 0) < 0) {
        throw JPtyException("Failed to set flags for master PTY!", platform.posix.errno)
      }

      Pty(PtyProcess(masterFd, pid))
    }
  }

  private fun processArgv(command: String, arguments: List<String>): List<String> {
    return if (arguments.isEmpty()) {
      listOf(command)
    } else if (arguments[0] != command) {
      listOf(command) + arguments
    } else {
      arguments
    }
  }

  private fun MemScope.allocWinsizePtr(winSize: WinSize): CPointer<winsize> {
    val value = alloc<winsize>()
    value.ws_col = winSize.columns.toUShort()
    value.ws_row = winSize.rows.toUShort()
    value.ws_xpixel = winSize.width.toUShort()
    value.ws_ypixel = winSize.height.toUShort()
    return value.ptr
  }

  private fun MemScope.cStringArray(values: List<String>): CPointer<CPointerVar<ByteVar>> {
    val cValues = values.map { it.cstr }
    val array = allocArray<CPointerVar<ByteVar>>(cValues.size + 1)
    cValues.forEachIndexed { index, cValue -> array[index] = cValue.ptr }
    array[cValues.size] = null
    return array
  }
}

private fun setWinSizeImpl(fd: Int, ws: WinSize): Int {
  return memScoped {
    val value = alloc<winsize>()
    value.ws_col = ws.columns.toUShort()
    value.ws_row = ws.rows.toUShort()
    value.ws_xpixel = ws.width.toUShort()
    value.ws_ypixel = ws.height.toUShort()
    ioctl(fd, TIOCSWINSZ.toULong(), value.ptr)
  }
}

internal class PtyProcess(var fd: Int, var pid: Int)

actual class Pty internal constructor(private val process: PtyProcess) {

  actual fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    checkState()
    require(offset >= 0 && length >= 0 && offset + length <= buffer.size) { "Invalid buffer range" }

    val result =
            buffer.usePinned { pinned ->
              read(process.fd, pinned.addressOf(offset), length.toULong())
            }

    if (result == 0L) {
      return -1
    }
    if (result < 0L) {
      throw JPtyException("I/O read failed", platform.posix.errno)
    }
    return result.toInt()
  }

  actual fun write(buffer: ByteArray, offset: Int, length: Int): Int {
    checkState()
    require(offset >= 0 && length >= 0 && offset + length <= buffer.size) { "Invalid buffer range" }

    var remaining = length
    var currentOffset = offset
    while (remaining > 0) {
      val written =
              buffer.usePinned { pinned ->
                write(process.fd, pinned.addressOf(currentOffset), remaining.toULong())
              }
      if (written < 0L) {
        throw JPtyException("I/O write failed", platform.posix.errno)
      }
      remaining -= written.toInt()
      currentOffset += written.toInt()
    }
    return length
  }

  actual fun close(terminateChild: Boolean) {
    if (process.fd != -1) {
      val flags = fcntl(process.fd, F_GETFL, 0)
      if (flags >= 0) {
        fcntl(process.fd, F_SETFL, flags or O_NONBLOCK)
      }

      if (terminateChild && isAlive()) {
        kill(process.pid, JPty.SIGKILL)
      }

      close(process.fd)
      process.fd = -1
      process.pid = -1
    }
  }

  actual fun isAlive(): Boolean {
    return memScoped {
      val stat = alloc<IntVar>()
      val result = waitpid(process.pid, stat.ptr, POSIX_WNOHANG)
      result == 0
    }
  }

  actual fun waitFor(): Int {
    if (process.pid < 0) {
      return -1
    }
    return memScoped {
      val status = alloc<IntVar>()
      waitpid(process.pid, status.ptr, 0)
      status.value
    }
  }

  actual fun interrupt(): Boolean {
    if (process.pid < 0) {
      return false
    }
    return kill(process.pid, SIGINT) == 0
  }

  actual fun destroy(): Boolean {
    if (process.pid < 0) {
      return false
    }
    return kill(process.pid, JPty.SIGKILL) == 0
  }

  actual fun getWinSize(): WinSize {
    val result = WinSize()
    val err = memScoped {
      val value = alloc<winsize>()
      val ret = ioctl(process.fd, TIOCGWINSZ.toULong(), value.ptr)
      if (ret == 0) {
        result.columns = value.ws_col.toInt()
        result.rows = value.ws_row.toInt()
        result.width = value.ws_xpixel.toInt()
        result.height = value.ws_ypixel.toInt()
      }
      ret
    }

    if (err < 0) {
      throw JPtyException("Failed to get window size", platform.posix.errno)
    }
    return result
  }

  actual fun setWinSize(winSize: WinSize) {
    if (setWinSizeImpl(process.fd, winSize) < 0) {
      throw JPtyException("Failed to set window size", platform.posix.errno)
    }
  }

  private fun checkState() {
    if (process.fd < 0) {
      throw IllegalStateException("Invalid file descriptor; PTY already closed?!")
    }
  }
}
