package jpty

class WinSize(
  var columns: Int = 0,
  var rows: Int = 0,
  var width: Int = 0,
  var height: Int = 0
) {
  fun toShortRows(): Short = rows.toShort()
  fun toShortCols(): Short = columns.toShort()
  fun toShortWidth(): Short = width.toShort()
  fun toShortHeight(): Short = height.toShort()
}