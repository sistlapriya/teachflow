package com.teachflow.agent.core

import android.util.Log

/** Structured logging. Callers must never pass credential text; redacted values arrive as "[REDACTED]". */
object TfLog {
    fun i(section: String, msg: String) { Log.i("TeachFlow", "[$section] $msg") }
    fun w(section: String, msg: String, t: Throwable? = null) { Log.w("TeachFlow", "[$section] $msg", t) }
}
