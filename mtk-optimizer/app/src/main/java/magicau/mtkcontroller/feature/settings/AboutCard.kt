package magicau.mtkcontroller.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import magicau.mtkcontroller.BuildConfig
import magicau.mtkcontroller.ui.components.InsetDivider
import magicau.mtkcontroller.ui.components.SettingsCard
import java.net.URL
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import magicau.mtkcontroller.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/** Everything shown in the about block. One place to edit contact details. */
private object Author {
    const val NAME = "Emotion04"
    const val QQ = "1695617871"
    const val WECHAT = "1695617871"
    const val EMAIL = "infpc@msn.com"
    const val GITHUB = "https://github.com/Emotion04"
    const val COOLAPK_UID = "3089423"
    const val COOLAPK_URL = "https://www.coolapk.com/u/$COOLAPK_UID"

    }

/**
 * Profile-style about block.
 *
 * Deliberately not a list of dead text: the avatar is fetched live from GitHub
 * and each row is tappable, opening the matching app where one exists (QQ has
 * a URL scheme; WeChat does not, so the id is copied instead).
 */
@Composable
fun AboutCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 头像打包在 res/drawable 里，不走网络。
    // 曾经的做法是每次打开这一页去 GitHub 拉一次，结果网络一抽风就只剩一个字母兜底，
    // 而且为了一张静态图每次都发一个第三方请求。图不变，就不该联网。
    val avatar = ImageBitmap.imageResource(R.drawable.avatar_author)

    SettingsCard(modifier = modifier) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(bitmap = avatar)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "MTK Controller",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "by ${Author.NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = {},
                        label = { Text("v${BuildConfig.VERSION_NAME}") },
                    )
                }
            }
        }

        InsetDivider(startIndent = 16.dp)

        ContactRow(
            icon = Icons.Filled.Person,
            tint = Color(0xFF12B7F5),
            label = stringResource(R.string.about_label_qq),
            value = Author.QQ,
            actionHint = stringResource(R.string.about_action_qq_card),
            copyValue = Author.QQ,
            onClick = {
                // 资料卡走 mqqapi://card/show_pslcard。mqq:// 与 mqqwpa:// 只能开聊天，
                // 陌生用户点进去会停在一个空会话，而不是先看到你是谁。
                val card = "mqqapi://card/show_pslcard" +
                    "?src_type=internal&version=1&uin=${Author.QQ}" +
                    "&card_type=person&source=qrcode"
                if (!context.tryOpen(card)) {
                    context.openUrl("https://qzone.qq.com/${Author.QQ}")
                }
            },
        )

        InsetDivider()
        ContactRow(
            icon = Icons.Filled.Share,
            tint = Color(0xFF07C160),
            label = stringResource(R.string.about_label_wechat),
            value = Author.WECHAT,
            actionHint = stringResource(R.string.about_action_copy_open),
            copyValue = Author.WECHAT,
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                // 微信没有开放的加好友接口（官方明确回复"不支持"），所以这里的
                // 动作是"复制 + 打开微信"，用户粘进搜索框即可。这比伪造一个会静默
                // 失败的 scheme 诚实。
                clipboard.setPrimaryClip(ClipData.newPlainText("wechat", Author.WECHAT))
                Toast.makeText(
                    context,
                    context.getString(R.string.about_wechat_copied),
                    Toast.LENGTH_LONG,
                ).show()
                context.tryOpen("weixin://")
            },
        )

        InsetDivider()
        ContactRow(
            icon = Icons.Filled.Email,
            tint = Color(0xFFEA4335),
            label = stringResource(R.string.about_label_email),
            value = Author.EMAIL,
            actionHint = stringResource(R.string.about_action_mail),
            copyValue = Author.EMAIL,
            onClick = { context.openUrl("mailto:${Author.EMAIL}") },
        )

        InsetDivider()
        ContactRow(
            icon = Icons.Filled.Star,
            tint = Color(0xFF6A4C93),
            label = stringResource(R.string.about_label_github),
            value = Author.NAME,
            actionHint = stringResource(R.string.about_action_homepage),
            copyValue = Author.GITHUB,
            onClick = { context.openUrl(Author.GITHUB) },
        )

        InsetDivider()
        ContactRow(
            icon = Icons.Filled.Star,
            tint = Color(0xFF11AA55),
            label = stringResource(R.string.about_label_coolapk),
            value = Author.COOLAPK_UID,
            actionHint = stringResource(R.string.about_action_homepage),
            copyValue = Author.COOLAPK_URL,
            onClick = {
                // 酷安没有公开的用户主页 deeplink 文档，所以先试通用 scheme，
                // 不成立就走网页 —— 网页版一定能打开，用户至少不会点进一片空白。
                if (!context.tryOpen("coolmarket://u/${Author.COOLAPK_UID}")) {
                    context.openUrl(Author.COOLAPK_URL)
                }
            },
        )

        InsetDivider(startIndent = 16.dp)
        Text(
            text = "versionCode ${BuildConfig.VERSION_CODE}，${BuildConfig.APPLICATION_ID}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun Avatar(bitmap: ImageBitmap?) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.about_avatar),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(62.dp),
            )
        } else {
            Text(
                "E",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    icon: ImageVector,
    tint: Color,
    label: String,
    value: String,
    actionHint: String,
    /** 长按复制的内容。联系方式要能被复制走，否则用户只能手抄。 */
    copyValue: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 整行都是点击目标，不只是末尾那个按钮 —— 一行里只有一小块能点，
            // 用户会先点空一次才知道要点哪。
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    clipboard.setText(AnnotatedString(copyValue))
                    Toast.makeText(
                        context,
                        context.getString(R.string.action_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AssistChip(onClick = onClick, label = { Text(actionHint) })
    }
}

private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Returns true when some installed app handled the scheme. */
private fun Context.tryOpen(url: String): Boolean = runCatching {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)
