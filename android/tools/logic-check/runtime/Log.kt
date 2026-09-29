package android.util
class Log { companion object {
    @JvmStatic fun i(tag: String?, msg: String?): Int = 0
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = 0
} }
