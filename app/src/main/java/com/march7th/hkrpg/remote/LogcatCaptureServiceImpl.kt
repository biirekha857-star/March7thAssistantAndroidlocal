package com.march7th.hkrpg.remote

import com.march7th.hkrpg.ILogcatService
import com.march7th.hkrpg.third.Ln
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

class LogcatCaptureServiceImpl : ILogcatService.Stub() {

    companion object {
        private const val TAG = "LogcatCapture"
    }

    private val watchTargets = ConcurrentHashMap<Int, Process>()

    init {
        Thread {
            while (true) {
                Thread.sleep(5000)
                watchTargets.forEach { (pid, process) ->
                    if (!File("/proc/$pid").exists()) {
                        Ln.i("$TAG: PID $pid gone, stopping its logcat")
                        process.destroyForcibly()
                        watchTargets.remove(pid)
                    }
                }
            }
        }.apply { name = "logcat-watchdog"; isDaemon = true }.start()
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun startCapture(appPid: Int, servicePid: Int, userDir: String) {
        val debugDir = File(userDir, "debug").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

        if (!watchTargets.containsKey(servicePid)) {
            val coreDir = File(debugDir, "logcat/core").apply { mkdirs() }
            val coreLog = File(coreDir, "logcat_$timestamp.log")
            Ln.i("$TAG: Capturing core PID $servicePid -> ${coreLog.absolutePath}")
            watchTargets[servicePid] = pipeLogcat(servicePid, coreLog)
        }

        if (!watchTargets.containsKey(appPid)) {
            val appDir = File(debugDir, "logcat/app").apply { mkdirs() }
            val appLog = File(appDir, "logcat_$timestamp.log")
            Ln.i("$TAG: Capturing app PID $appPid -> ${appLog.absolutePath}")
            watchTargets[appPid] = pipeLogcat(appPid, appLog)
        }
    }

    private fun pipeLogcat(pid: Int, outFile: File): Process {
        val process = ProcessBuilder("logcat", "-T", "10", "--pid=$pid")
            .redirectErrorStream(true)
            .start()

        Thread {
            try {
                process.inputStream.use { input ->
                    FileOutputStream(outFile, true).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                Ln.e("$TAG: pipeLogcat pid=$pid failed: ${e.message}")
            }
        }.apply { name = "logcat-reader-$pid" }.start()

        return process
    }
}
