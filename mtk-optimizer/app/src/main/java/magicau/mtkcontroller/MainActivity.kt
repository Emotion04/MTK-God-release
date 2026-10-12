package magicau.mtkcontroller

import androidx.compose.ui.res.stringResource

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import magicau.mtkcontroller.data.privilege.PrivilegeManager
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import magicau.mtkcontroller.domain.model.ThemeMode
import magicau.mtkcontroller.ui.navigation.AppNavHost
import magicau.mtkcontroller.ui.theme.AppPalette
import magicau.mtkcontroller.ui.theme.MtkOptimizerTheme
import magicau.mtkcontroller.ui.theme.ShapeStyle
import magicau.mtkcontroller.data.settings.SettingsRepository
import magicau.mtkcontroller.ui.theme.MorandiAccent
import magicau.mtkcontroller.ui.theme.MotionSpeed

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        PrivilegeManager.refresh()
        if (PrivilegeManager.state.value.permissionGranted) PrivilegeManager.bindUserService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MtkApp).container
        setContent {
            val paletteName by container.settingsRepository.themePalette
                .collectAsStateWithLifecycle(initialValue = AppPalette.SYSTEM.name)
            val themeModeName by container.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM.name)
            val shapeStyleName by container.settingsRepository.shapeStyle
                .collectAsStateWithLifecycle(initialValue = ShapeStyle.STANDARD.name)
            val motionSpeedName by container.settingsRepository.motionSpeed
                .collectAsStateWithLifecycle(initialValue = MotionSpeed.SYSTEM.name)
            // 自定义强调色的两个输入。只在选了「自定义」时用得上，但两个 Flow 都常驻
            // 收集 —— 换来的是切到自定义那一档时立刻变色，不用先等一次读取。
            val customHue by container.settingsRepository.customHue
                .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_CUSTOM_HUE)
            val customConcentration by container.settingsRepository.customConcentration
                .collectAsStateWithLifecycle(
                    initialValue = SettingsRepository.DEFAULT_CUSTOM_CONCENTRATION,
                )

            val palette = AppPalette.fromName(paletteName)
            MtkOptimizerTheme(
                themeMode = ThemeMode.fromName(themeModeName),
                palette = palette,
                // 颜色在这里推导，不读存好的 ARGB —— 保证"白字可读"那条不变量
                // 始终走同一条路径。见 MorandiAccent。
                accentOverride = if (palette == AppPalette.CUSTOM) {
                    MorandiAccent.fromHueSaturation(
                        customHue.toFloat(),
                        customConcentration / 100f,
                    )
                } else {
                    null
                },
                shapeStyle = ShapeStyle.fromName(shapeStyleName),
                motionSpeed = MotionSpeed.fromName(motionSpeedName),
            ) {
                AppNavHost(container = container, modifier = Modifier.fillMaxSize())
                val privilege by PrivilegeManager.state.collectAsStateWithLifecycle()
                var dismissed by rememberSaveable { mutableStateOf(false) }
                if (!dismissed && !privilege.permissionGranted) {
                    AlertDialog(
                        onDismissRequest = { dismissed = true },
                        title = { Text(stringResource(R.string.onboarding_authorize_title)) },
                        text = { Text(privilege.message ?: if (privilege.binderAlive) {
                            stringResource(R.string.onboarding_not_granted)
                        } else {
                            stringResource(R.string.onboarding_not_running)
                        }) },
                        confirmButton = {
                            TextButton(onClick = {
                                PrivilegeManager.requestPermission()
                                if (PrivilegeManager.state.value.binderAlive) dismissed = true
                            }) { Text(stringResource(R.string.action_authorize)) }
                        },
                        dismissButton = { TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.action_skip)) } },
                    )
                }
            }
        }
    }
}
