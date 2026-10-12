package magicau.mtkcontroller.feature.tweaks

import magicau.mtkcontroller.ui.components.LocalNavBarClearance
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import magicau.mtkcontroller.ui.components.*
import magicau.mtkcontroller.ui.navigation.SubRoutes

@Composable
fun TuningScreen(onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp).padding(bottom = LocalNavBarClearance.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("调校", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(6.dp))
        SectionLabel("系统配置")
        SettingsCard { SettingsRow(Icons.Default.Star, MaterialTheme.colorScheme.primary, "vivo 智算平台", "状态读取、实验配置与备份恢复", onClick = { onOpen(SubRoutes.SMART) }) }
        SectionLabel("实用工具")
        SettingsCard {
            SettingsRow(Icons.Default.Build, MaterialTheme.colorScheme.secondary, "显示与交互", "刷新率、亮度、触控与画面", onClick = { onOpen(SubRoutes.TWEAKS) })
            InsetDivider()
            SettingsRow(Icons.Default.Settings, MaterialTheme.colorScheme.tertiary, "系统与网络配置", "可恢复配置、动画与私人 DNS", onClick = { onOpen(SubRoutes.CONFIG) })
            InsetDivider()
            SettingsRow(Icons.Default.Refresh, MaterialTheme.colorScheme.primary, "应用维护", "编译优化与充电预热", onClick = { onOpen(SubRoutes.MAINTENANCE) })
            InsetDivider()
            SettingsRow(Icons.Default.PlayArrow, MaterialTheme.colorScheme.error, "游戏与画面实验", "帧率解锁、后台放开、画面时序与假电量", onClick = { onOpen(SubRoutes.GAME) })
            InsetDivider()
            SettingsRow(Icons.Default.Info, MaterialTheme.colorScheme.primary, "本机状态与工具", "温度、GPU、DDR、内存实时读数，以及系统页面直达", onClick = { onOpen(SubRoutes.STATUS) })
        }
    }
}
