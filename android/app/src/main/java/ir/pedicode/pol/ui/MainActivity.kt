package ir.pedicode.pol.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.pedicode.pol.BridgeService
import ir.pedicode.pol.BridgeState
import ir.pedicode.pol.data.License
import ir.pedicode.pol.data.Prefs
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0B0D10)
private val Card = Color(0xFF12161B)
private val Field = Color(0xFF1A1F26)
private val Accent = Color(0xFF34D399)
private val AccentDark = Color(0xFF06281A)
private val Muted = Color(0xFF8A93A0)
private val Text2 = Color(0xFFA3ACB8)
private val Warn = Color(0xFFF5B547)

private val PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.READ_SMS,
    Manifest.permission.SEND_SMS,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_PHONE_NUMBERS,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.ANSWER_PHONE_CALLS,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.POST_NOTIFICATIONS,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    var splash by remember { mutableStateOf(true) }
                    LaunchedEffect(Unit) { delay(1200); splash = false }
                    Box(Modifier.fillMaxSize().background(Bg)) {
                        AnimatedVisibility(!splash, enter = fadeIn(), exit = fadeOut()) { Home() }
                        AnimatedVisibility(splash, enter = fadeIn(), exit = fadeOut()) { Splash() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Splash() {
    Column(
        Modifier.fillMaxSize().background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("پل", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text("اندروید ↔ آیفون", fontSize = 15.sp, color = Muted)
        Spacer(Modifier.height(48.dp))
        Credit()
    }
}

/** "from" in small lowercase, PEDICODE below it; opens pedicode.ir. */
@Composable
fun Credit() {
    val context = LocalContext.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier
                .clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://pedicode.ir")))
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("from", fontSize = 11.sp, color = Muted, letterSpacing = 1.sp)
            Text("PEDICODE", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Accent, letterSpacing = 3.sp)
        }
    }
}

@Composable
private fun Home() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val status by BridgeState.status.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf(prefs.pairingCode) }
    var granted by remember { mutableIntStateOf(countGranted(context)) }
    var batteryOk by remember { mutableStateOf(ignoresBattery(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = countGranted(context)
        batteryOk = ignoresBattery(context)
    }
    LaunchedEffect(status.running) {
        granted = countGranted(context)
        batteryOk = ignoresBattery(context)
    }
    val allGranted = granted == PERMISSIONS.size

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("پل", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)

        // Status
        Section {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(10.dp).background(
                        if (status.connected) Accent else if (status.running) Warn else Muted, CircleShape,
                    )
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            status.connected -> "متصل به ${status.iphoneName ?: "آیفون"}"
                            status.running -> "منتظر اتصال آیفون…"
                            else -> "خاموش"
                        },
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    )
                    Text("از طریق بلوتوث", fontSize = 13.sp, color = Muted)
                }
                Switch(
                    checked = status.running,
                    enabled = allGranted,
                    onCheckedChange = { on ->
                        if (on) BridgeService.start(context) else BridgeService.stop(context)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = AccentDark),
                )
            }
        }

        // Pairing code
        Section {
            Text("کد جفت‌سازی", fontSize = 14.sp, color = Muted)
            Text(
                code.chunked(3).joinToString("  "),
                fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Accent,
                letterSpacing = 4.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            Text(
                "این کد را یک بار در اپ آیفون وارد کنید.",
                fontSize = 13.sp, color = Text2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = { prefs.forgetAll(); code = prefs.pairingCode },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
            ) { Text("کد جدید و قطع آیفون‌های قبلی", color = Text2) }
        }

        // Setup steps
        Section {
            Text("راه‌اندازی", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Step(
                done = allGranted,
                title = "۱. دادن مجوزها ($granted از ${PERMISSIONS.size})",
                hint = "پیامک، تماس، مخاطبین، بلوتوث و اعلان",
                action = "دادن مجوز",
            ) { permissionLauncher.launch(PERMISSIONS) }
            Step(
                done = false,
                title = "۲. اجازه‌ی تنظیمات محدود",
                hint = "اگر مجوز پیامک خاکستری بود: اطلاعات برنامه ← منوی ⋮ ← Allow restricted settings",
                action = "اطلاعات برنامه",
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                )
            }
            Step(
                done = batteryOk,
                title = "۳. خاموش کردن بهینه‌سازی باتری",
                hint = "تا گوشی برنامه را در پس‌زمینه نبندد",
                action = "تنظیم",
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }
        }

        // Subscription
        Section {
            val deviceCode = remember { License.deviceCode(context) }
            var licenseCode by remember { mutableStateOf(prefs.licenseCode ?: "") }
            var info by remember { mutableStateOf(License.current(context, prefs)) }
            var error by remember { mutableStateOf<String?>(null) }
            val days = License.trialDaysLeft(prefs.firstRun)
            val clipboard = LocalClipboardManager.current

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("اشتراک", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                Text(
                    when {
                        info != null -> "${info!!.planName} · ${info!!.daysLeft} روز مانده"
                        days > 0 -> "$days روز آزمایشی مانده"
                        else -> "غیرفعال"
                    },
                    fontSize = 13.sp,
                    color = if (info != null) Accent else Warn,
                )
            }
            if (info == null) {
                License.plans.forEach { plan ->
                    Row(
                        Modifier.fillMaxWidth().background(Field, RoundedCornerShape(12.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(plan.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(plan.note, fontSize = 12.sp, color = Muted)
                        }
                        Text(plan.price, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Accent)
                    }
                }
            }
            // Device code: the customer sends this when buying.
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Field, RoundedCornerShape(12.dp))
                    .clickable { clipboard.setText(AnnotatedString(deviceCode)) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("کد دستگاه (برای خرید بفرستید)", fontSize = 12.sp, color = Muted)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(deviceCode, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 2.sp)
                    }
                }
                Text("کپی", fontSize = 13.sp, color = Accent)
            }
            OutlinedTextField(
                value = licenseCode,
                onValueChange = { licenseCode = it.trim(); error = null },
                label = { Text("کد لایسنس") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val v = License.verify(licenseCode, deviceCode)
                        if (v != null) {
                            prefs.licenseCode = licenseCode
                            info = v
                            error = null
                        } else {
                            error = "کد معتبر نیست یا برای این گوشی نیست"
                        }
                    },
                    enabled = licenseCode.isNotBlank(),
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentDark),
                ) { Text("فعال‌سازی", fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(License.BUY_URL))) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("خرید اشتراک", color = Accent) }
            }
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Credit() }
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Step(done: Boolean, title: String, hint: String, action: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = if (done) Accent else Color.White, fontWeight = FontWeight.Medium)
            Text(hint, fontSize = 12.sp, color = Muted, lineHeight = 18.sp)
        }
        Spacer(Modifier.size(8.dp))
        if (done) {
            Text("✓", fontSize = 20.sp, color = Accent)
        } else {
            OutlinedButton(onClick = onClick, shape = RoundedCornerShape(10.dp)) { Text(action, color = Accent, fontSize = 13.sp) }
        }
    }
}

private fun countGranted(context: android.content.Context): Int =
    PERMISSIONS.count { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

private fun ignoresBattery(context: android.content.Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
