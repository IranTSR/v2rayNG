package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
object SstpManager {
    private var process: Process? = null

    @Synchronized
    fun startForRun(context: Context, profile: ProfileItem?): Boolean {
        stop()
        if (profile == null || profile.configType != EConfigType.SSTP) {
            return true
        }
        val binFile = File(context.applicationInfo.nativeLibraryDir, AppConfig.SSTP_SOCKS_BIN)
        if (!binFile.exists()) {
            LogUtil.e(AppConfig.TAG, "SSTP binary not found: ${binFile.absolutePath}")
            return false
        }
        val cmd = mutableListOf(
            binFile.absolutePath,
            "-listen", "${AppConfig.LOOPBACK}:${AppConfig.SSTP_LOCAL_PORT}",
            "-server", profile.server.orEmpty(),
            "-port", (profile.serverPort?.toIntOrNull() ?: 443).toString(),
            "-user", profile.username.orEmpty(),
            "-pass", profile.password.orEmpty()
        )
        if (!profile.sni.isNullOrBlank()) {
            cmd.add("-sni")
            cmd.add(profile.sni!!)
        }
        if (profile.insecure == true) {
            cmd.add("-insecure")
        }
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process = proc
            for (i in 0 until 40) {
                if (!proc.isAlive) {
                    val err = proc.inputStream.bufferedReader().readText()
                    LogUtil.e(AppConfig.TAG, "sstp-socks exited early: $err")
                    stop()
                    return false
                }
                try {
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(AppConfig.LOOPBACK, AppConfig.SSTP_LOCAL_PORT), 200)
                    }
                    return true
                } catch (_: Exception) {
                    Thread.sleep(200)
                }
            }
            LogUtil.e(AppConfig.TAG, "sstp-socks listen timeout")
            stop()
            false
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to start sstp-socks", e)
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            process?.destroy()
        } catch (_: Exception) {
        } finally {
            process = null
        }
    }
}
