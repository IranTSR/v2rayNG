package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.compose.FormPasswordField
import com.v2ray.ang.ui.compose.FormTextField

class ServerSstpActivity : BaseServerActivity() {
    override val serverConfigType: EConfigType = EConfigType.SSTP

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig).apply {
                configType = EConfigType.SSTP
                if (initialConfig.serverPort.isNullOrBlank()) port = "443"
            }
        }.apply {
            configType = EConfigType.SSTP
        }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveServer(uiState) }
        ) {
            CommonBasicFields(uiState)
            FormTextField(
                label = stringResource(R.string.server_lab_username),
                value = uiState.username,
                onValueChange = { uiState.username = it },
                isError = uiState.username.isBlank()
            )
            FormPasswordField(
                label = stringResource(R.string.server_lab_password),
                value = uiState.password,
                onValueChange = { uiState.password = it },
                isError = uiState.password.isBlank()
            )
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        val port = config.serverPort?.toIntOrNull()
        if (port == null || port !in 1..65535) {
            toast(R.string.toast_config_file_invalid)
            return false
        }
        if (config.username.isNullOrBlank() || config.password.isNullOrBlank()) {
            toast(R.string.toast_config_file_invalid)
            return false
        }
        return true
    }
}
