package com.pai.android.agent.skills

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Проверка сгенерированного Python-кода на работоспособность:
 *  - синтаксис через ast.parse (без выполнения)
 *  - smoke-запуск (быстрый прогон, ловит рантайм-ошибки)
 * init() вызывается из PaiApplication, где Python уже стартует.
 */
internal object PythonVerifier {

    @Volatile private var ctx: Context? = null

    fun init(context: Context) { ctx = context.applicationContext }

    data class Result(val ok: Boolean, val stage: String, val message: String)

    private fun ensureStarted(): Boolean {
        return try {
            if (!Python.isStarted()) {
                val c = ctx ?: return false
                Python.start(AndroidPlatform(c))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Синтаксическая проверка: ast.parse бросает исключение на битом коде. */
    fun checkSyntax(code: String): Result {
        if (code.isBlank()) return Result(false, "syntax", "пустой код")
        if (!ensureStarted()) return Result(false, "syntax", "Python runtime недоступен")
        return try {
            Python.getInstance().getModule("ast").callAttr("parse", code)
            Result(true, "syntax", "OK")
        } catch (e: Exception) {
            Result(false, "syntax", e.message ?: "syntax error")
        }
    }

    /** Smoke-запуск: выполняет код с пустым query, ловит рантайм-исключения. */
    fun smokeRun(code: String, input: String = ""): Result {
        if (!ensureStarted()) return Result(false, "run", "Python runtime недоступен")
        return try {
            val py = Python.getInstance()
            val builtins = py.getModule("builtins")
            val g = builtins.callAttr("dict")
            g.callAttr("__setitem__", "__name__", "__smoke__")
            g.callAttr("__setitem__", "query", input)
            builtins.callAttr("exec", code, g)
            Result(true, "run", "OK")
        } catch (e: Exception) {
            Result(false, "run", e.message ?: "runtime error")
        }
    }

    fun describe(r: Result): String = if (r.ok) r.stage + ": OK" else r.stage + ": " + r.message.take(200)
}