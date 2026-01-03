package jpty

expect object JPty {
  val SIGKILL: Int
  val WNOHANG: Int

  fun execInPTY(
          command: String,
          arguments: List<String> = emptyList(),
          environment: Map<String, String>? = null,
          workingDirectory: String? = null,
          winSize: WinSize? = null
  ): Pty
}

expect class Pty {
  fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int
  fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int
  fun close(terminateChild: Boolean = true)
  fun isAlive(): Boolean
  fun waitFor(): Int
  fun interrupt(): Boolean
  fun destroy(): Boolean
  fun getWinSize(): WinSize
  fun setWinSize(winSize: WinSize)
}
