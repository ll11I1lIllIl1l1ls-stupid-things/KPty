package jpty

class JPtyException(message: String, val errno: Int) : RuntimeException(message)