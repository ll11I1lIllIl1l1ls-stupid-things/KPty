@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package jpty

import jpty.pty.ClosePseudoConsole as ConptyClosePseudoConsole
import jpty.pty.CreatePseudoConsole as ConptyCreatePseudoConsole
import jpty.pty.HPCON
import jpty.pty.HPCONVar
import jpty.pty.ResizePseudoConsole as ConptyResizePseudoConsole
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.COORD
import platform.windows.CTRL_C_EVENT
import platform.windows.CloseHandle
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.DWORDVar
import platform.windows.DeleteProcThreadAttributeList
import platform.windows.GenerateConsoleCtrlEvent
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.HANDLEVar
import platform.windows.InitializeProcThreadAttributeList
import platform.windows.PROCESS_INFORMATION
import platform.windows.ReadFile
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.SIZE_TVar
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOEXW
import platform.windows.STARTUPINFOW
import platform.windows.TerminateProcess
import platform.windows.UpdateProcThreadAttribute
import platform.windows.WCHARVar
import platform.windows.WaitForSingleObject
import platform.windows.WriteFile

private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24
private const val PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE_VALUE = 0x00020016u
private const val EXTENDED_STARTUPINFO_PRESENT_VALUE = 0x00080000u
private const val CREATE_UNICODE_ENVIRONMENT_VALUE = 0x00000400u
private const val CREATE_NEW_PROCESS_GROUP_VALUE = 0x00000200u
private const val ERROR_BROKEN_PIPE_VALUE = 109
private const val STILL_ACTIVE_VALUE = 259
private const val WAIT_OBJECT_0_VALUE = 0u
private const val INFINITE_VALUE = 0xFFFFFFFFu
private const val EXIT_CODE_TERMINATED = 1u

actual object JPty {
    actual val SIGKILL: Int = 9
    actual val WNOHANG: Int = 1

    actual fun execInPTY(
            command: String,
            arguments: List<String>,
            environment: Map<String, String>?,
            workingDirectory: String?,
            winSize: WinSize?
    ): Pty {
        val cmdLine = buildCommandLine(command, arguments)
        val effectiveWinSize = winSize ?: WinSize(DEFAULT_COLUMNS, DEFAULT_ROWS, 0, 0)

        return memScoped {
            val inputRead = alloc<HANDLEVar>()
            val inputWrite = alloc<HANDLEVar>()
            val outputRead = alloc<HANDLEVar>()
            val outputWrite = alloc<HANDLEVar>()
            val security = alloc<SECURITY_ATTRIBUTES>()
            security.nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
            security.lpSecurityDescriptor = null
            security.bInheritHandle = 1

            if (CreatePipe(inputRead.ptr, inputWrite.ptr, security.ptr, 0u) == 0) {
                throw JPtyException("Failed to create input pipe", GetLastError().toInt())
            }
            if (CreatePipe(outputRead.ptr, outputWrite.ptr, security.ptr, 0u) == 0) {
                CloseHandle(inputRead.value)
                CloseHandle(inputWrite.value)
                throw JPtyException("Failed to create output pipe", GetLastError().toInt())
            }

            val size = alloc<COORD>()
            size.X = effectiveWinSize.columns.toShort()
            size.Y = effectiveWinSize.rows.toShort()

            val hPC = alloc<HPCONVar>()
            val hr =
                    ConptyCreatePseudoConsole(
                            size.readValue(),
                            inputRead.value,
                            outputWrite.value,
                            0u,
                            hPC.ptr
                    )
            CloseHandle(inputRead.value)
            CloseHandle(outputWrite.value)
            if (hr.toInt() != 0) {
                CloseHandle(inputWrite.value)
                CloseHandle(outputRead.value)
                throw JPtyException("Failed to open PTY", hr.toInt())
            }

            val attrListSize = alloc<SIZE_TVar>()
            InitializeProcThreadAttributeList(null, 1u, 0u, attrListSize.ptr)
            val attrListBuffer = allocArray<ByteVar>(attrListSize.value.toInt())
            val attrList = attrListBuffer.reinterpret<cnames.structs._PROC_THREAD_ATTRIBUTE_LIST>()
            if (InitializeProcThreadAttributeList(attrList, 1u, 0u, attrListSize.ptr) == 0) {
                ConptyClosePseudoConsole(hPC.value)
                CloseHandle(inputWrite.value)
                CloseHandle(outputRead.value)
                throw JPtyException("Failed to init proc attribute list", GetLastError().toInt())
            }

            val updateOk =
                    UpdateProcThreadAttribute(
                            attrList,
                            0u,
                            PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE_VALUE.toULong(),
                            hPC.value,
                            sizeOf<HPCONVar>().toULong(),
                            null,
                            null
                    )
            if (updateOk == 0) {
                DeleteProcThreadAttributeList(attrList)
                ConptyClosePseudoConsole(hPC.value)
                CloseHandle(inputWrite.value)
                CloseHandle(outputRead.value)
                throw JPtyException(
                        "Failed to set pseudo console attribute",
                        GetLastError().toInt()
                )
            }

            val startupInfo = alloc<STARTUPINFOEXW>()
            startupInfo.StartupInfo.cb = sizeOf<STARTUPINFOEXW>().toUInt()
            startupInfo.lpAttributeList = attrList
            startupInfo.StartupInfo.dwFlags = STARTF_USESTDHANDLES.toUInt()

            val processInfo = alloc<PROCESS_INFORMATION>()
            val envBlock = buildEnvironmentBlock(environment, this)
            val commandPtr = cmdLine.wcstr.ptr
            val creationFlags =
                    (EXTENDED_STARTUPINFO_PRESENT_VALUE or CREATE_NEW_PROCESS_GROUP_VALUE) or
                            /*if (envBlock == null) 0u else */ CREATE_UNICODE_ENVIRONMENT_VALUE
            val created =
                    CreateProcessW(
                            null,
                            commandPtr,
                            null,
                            null,
                            1,
                            creationFlags,
                            envBlock,
                            workingDirectory,
                            startupInfo.ptr.reinterpret<STARTUPINFOW>(),
                            processInfo.ptr
                    )

            DeleteProcThreadAttributeList(attrList)
            if (created == 0) {
                val err = GetLastError().toInt()
                ConptyClosePseudoConsole(hPC.value)
                CloseHandle(inputWrite.value)
                CloseHandle(outputRead.value)
                throw JPtyException("Failed to create process (GetLastError=$err)", err)
            }

            val pid = processInfo.dwProcessId.toInt()

            val process =
                    PtyProcess(
                            hPC.value,
                            inputWrite.value,
                            outputRead.value,
                            processInfo.hProcess,
                            processInfo.hThread,
                            effectiveWinSize,
                            pid
                    )

            Pty(process)
        }
    }

    private fun buildCommandLine(command: String, arguments: List<String>): String {
        val args =
                if (arguments.isEmpty()) {
                    listOf(command)
                } else if (arguments[0] != command) {
                    listOf(command) + arguments
                } else {
                    arguments
                }
        return args.joinToString(" ") { quoteWindowsArg(it) }
    }

    private fun quoteWindowsArg(arg: String): String {
        if (arg.isEmpty()) {
            return "\"\""
        }
        val needsQuotes = arg.any { it == ' ' || it == '\t' || it == '"' }
        if (!needsQuotes) {
            return arg
        }
        val sb = StringBuilder()
        sb.append('"')
        var backslashes = 0
        for (ch in arg) {
            if (ch == '\\') {
                backslashes++
                continue
            }
            if (ch == '"') {
                sb.append("\\".repeat(backslashes * 2 + 1))
                sb.append('"')
                backslashes = 0
                continue
            }
            if (backslashes > 0) {
                sb.append("\\".repeat(backslashes))
                backslashes = 0
            }
            sb.append(ch)
        }
        if (backslashes > 0) {
            sb.append("\\".repeat(backslashes * 2))
        }
        sb.append('"')
        return sb.toString()
    }

    private fun buildEnvironmentBlock(
            environment: Map<String, String>?,
            scope: MemScope
    ): CPointer<WCHARVar>? {
        if (environment == null) {
            return null
        }
        val block =
                environment.entries.joinToString("\u0000", postfix = "\u0000\u0000") {
                    "${it.key}=${it.value}"
                }
        return block.wcstr.getPointer(scope)
    }

    private fun allocWideStringMutable(scope: MemScope, value: String): CPointer<WCHARVar> {
        val buffer = scope.allocArray<WCHARVar>(value.length + 1)
        for (i in value.indices) {
            buffer[i] = value[i].code.toUShort()
        }
        buffer[value.length] = 0u
        return buffer
    }
}

internal class PtyProcess(
        val hPC: HPCON?,
        val hInputWrite: HANDLE?,
        val hOutputRead: HANDLE?,
        val hProcess: HANDLE?,
        val hThread: HANDLE?,
        var winSize: WinSize,
        val pid: Int
)

actual class Pty internal constructor(private val process: PtyProcess) {

    private var closed = false

    actual fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkState()
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "Invalid buffer range"
        }

        return memScoped {
            val readCount = alloc<DWORDVar>()
            val ok =
                    buffer.usePinned { pinned ->
                        ReadFile(
                                process.hOutputRead,
                                pinned.addressOf(offset),
                                length.toUInt(),
                                readCount.ptr,
                                null
                        )
                    }
            if (ok == 0) {
                val err = GetLastError().toInt()
                if (err == ERROR_BROKEN_PIPE_VALUE) {
                    return@memScoped -1
                }
                throw JPtyException("I/O read failed", err)
            }
            val result = readCount.value.toInt()
            if (result == 0) {
                -1
            } else {
                result
            }
        }
    }

    actual fun write(buffer: ByteArray, offset: Int, length: Int): Int {
        checkState()
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "Invalid buffer range"
        }

        var remaining = length
        var currentOffset = offset
        while (remaining > 0) {
            val written = memScoped {
                val writeCount = alloc<DWORDVar>()
                val ok =
                        buffer.usePinned { pinned ->
                            WriteFile(
                                    process.hInputWrite,
                                    pinned.addressOf(currentOffset),
                                    remaining.toUInt(),
                                    writeCount.ptr,
                                    null
                            )
                        }
                if (ok == 0) {
                    throw JPtyException("I/O write failed", GetLastError().toInt())
                }
                writeCount.value.toInt()
            }
            remaining -= written
            currentOffset += written
        }
        return length
    }

    actual fun close(terminateChild: Boolean) {
        if (closed) return
        closed = true

        if (terminateChild && isAlive()) {
            process.hProcess?.let { TerminateProcess(it, EXIT_CODE_TERMINATED) }
        }

        process.hInputWrite?.let { CloseHandle(it) }
        process.hOutputRead?.let { CloseHandle(it) }
        process.hThread?.let { CloseHandle(it) }
        process.hProcess?.let { CloseHandle(it) }
        process.hPC?.let { ConptyClosePseudoConsole(it) }
    }

    actual fun isAlive(): Boolean {
        if (closed) return false
        return memScoped {
            val code = alloc<DWORDVar>()
            if (GetExitCodeProcess(process.hProcess, code.ptr) == 0) {
                return@memScoped false
            }
            code.value.toInt() == STILL_ACTIVE_VALUE
        }
    }

    actual fun waitFor(): Int {
        // waitFor() must be called before close() since the process handle becomes invalid after
        // closing.
        if (closed) return -1

        val waitResult = WaitForSingleObject(process.hProcess, INFINITE_VALUE)
        if (waitResult == WAIT_OBJECT_0_VALUE) {
            return memScoped {
                val code = alloc<DWORDVar>()
                GetExitCodeProcess(process.hProcess, code.ptr)
                code.value.toInt()
            }
        }
        return -1
    }

    actual fun interrupt(): Boolean {
        if (closed) return false
        return GenerateConsoleCtrlEvent(CTRL_C_EVENT.toUInt(), process.pid.toUInt()) != 0
    }

    actual fun destroy(): Boolean {
        if (closed) return false
        return TerminateProcess(process.hProcess, EXIT_CODE_TERMINATED) != 0
    }

    actual fun getWinSize(): WinSize {
        if (closed) throw IllegalStateException("Pty is closed")
        return process.winSize
    }

    actual fun setWinSize(winSize: WinSize) {
        if (closed) throw IllegalStateException("Pty is closed")
        memScoped {
            val size = alloc<COORD>()
            size.X = winSize.columns.toShort()
            size.Y = winSize.rows.toShort()
            val result = ConptyResizePseudoConsole(process.hPC, size.readValue())
            if (result.toInt() == 0) {
                process.winSize = winSize
            } else {
                throw JPtyException("Failed to set window size", result.toInt())
            }
        }
    }

    private fun checkState() {
        if (closed) throw IllegalStateException("Pty is closed")
    }
}
