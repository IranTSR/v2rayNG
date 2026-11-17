package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.util.Utils
import java.net.URI

object SstpFmt : FmtBase() {
    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.SSTP)
        val uri = URI(Utils.fixIllegalUrl(str))
        if (uri.idnHost.isEmpty()) return null
        config.remarks = Utils.urlDecode(uri.fragment.orEmpty()).let { it.ifEmpty { "none" } }
        config.server = uri.idnHost
        config.serverPort = (if (uri.port > 0) uri.port else 443).toString()

        if (uri.userInfo.isNotNullEmpty()) {
            val raw = if (uri.userInfo.contains(":")) {
                uri.userInfo.split(":", limit = 2)
            } else {
                Utils.decode(uri.userInfo).split(":", limit = 2)
            }
            if (raw.size == 2) {
                config.username = raw[0]
                config.password = raw[1]
            }
        }

        val queryParam = getQueryParam(uri)
        config.sni = queryParam["sni"]
        config.insecure = queryParam["insecure"] == "1" || queryParam["insecure"].equals("true", true)
        return config
    }

    fun toUri(config: ProfileItem): String {
        val pw = if (config.username.isNotNullEmpty()) "${config.username}:${config.password.orEmpty()}" else ":"
        val dicQuery = hashMapOf<String, String>()
        if (!config.sni.isNullOrBlank()) {
            dicQuery["sni"] = config.sni!!
        }
        if (config.insecure == true) {
            dicQuery["insecure"] = "1"
        }
        return toUri(config, Utils.encode(pw, true), dicQuery)
    }
}
