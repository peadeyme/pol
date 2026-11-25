package ir.pedicode.pol.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.pedicode.pol.BridgeService
import ir.pedicode.pol.BridgeState
import ir.pedicode.pol.ble.BridgeStats
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

/** Runtime permissions this Android version actually has. */
private val PERMISSIONS: Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_PHONE_NUMBERS)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.ANSWER_PHONE_CALLS)
    add(Manifest.permission.READ_CONTACTS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

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
    LaunchedEffect(code) { BridgeState.update { it.copy(shownCode = code) } }
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
    // Re-check permissions whenever the user comes back from Settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = countGranted(context)
                batteryOk = ignoresBattery(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Shows the switch flipped right away while the service starts or stops.
    var wanted by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(status.running) { wanted = null }
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
                            status.unsupported -> "بلوتوث این گوشی از پل پشتیبانی نمی‌کند"
                            status.bluetoothOff -> "بلوتوث خاموش است"
                            status.connected -> "متصل به ${status.iphoneName ?: "آیفون"}"
                            status.running -> "منتظر اتصال آیفون…"
                            else -> "خاموش"
                        },
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    )
                    Text("از طریق بلوتوث", fontSize = 13.sp, color = Muted)
                }
                Switch(
                    checked = wanted ?: status.running,
                    enabled = allGranted,
                    onCheckedChange = { on ->
                        wanted = on
                        if (on) BridgeService.start(context) else BridgeService.stop(context)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = AccentDark),
                )
            }
        }

        // Live diagnostics (helps when something doesn't reach the iPhone)
        if (status.running) {
            var tick by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
            Section {
                Text("وضعیت لحظه‌ای", fontSize = 14.sp, color = Muted)
                key(tick) {
                    Text(
                        "بسته‌های ارسالی: ${BridgeStats.tx} · دریافتی: ${BridgeStats.rx}",
                        fontSize = 13.sp, color = Text2,
                    )
                }
                Text(status.lastEvent ?: "هنوز رویدادی نیست", fontSize = 13.sp, color = Color.White)
            }
        }

        // Pairing code
        Section {
            Text("کد جفت‌سازی", fontSize = 14.sp, color = Muted)
            // Digits must be shown left-to-right, or "327 291" appears as "291 327" in RTL.
            Text(
                "\u2066" + code.chunked(3).joinToString("  ") + "\u2069",
                fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Accent,
                letterSpacing = 4.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            status.lastRejected?.let {
                Text(
                    "تلاش ناموفق آیفون: $it",
                    fontSize = 13.sp, color = Color(0xFFF87171), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                )
            }
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
            if (Build.VERSION.SDK_INT >= 33) Step(
                done = false,
                title = "۲. اجازه‌ی تنظیمات محدود",
                hint = "اگر مجوز پیامک خاکستری بود: اطلاعات برنامه ← منوی ⋮ ← Allow restricted settings",
                action = "اطلاعات برنامه",
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                )
            }
            if (isXiaomi()) {
                Step(
                    done = false,
                    title = "شیائومی/پوکو: اجازه‌های اضافه",
                    hint = "«Display pop-up windows while running in background» و «Start in background» را روشن کنید؛ بدون این، شماره‌گیری از آیفون کار نمی‌کند",
                    action = "باز کردن",
                ) { openXiaomiPermissions(context) }
                Step(
                    done = false,
                    title = "شیائومی/پوکو: Autostart",
                    hint = "پل را در لیست Autostart روشن کنید تا بعد از ری‌استارت و در پس‌زمینه بسته نشود",
                    action = "باز کردن",
                ) { openXiaomiAutostart(context) }
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
                Text(
                    "برای خرید یا تمدید، کد دستگاه را کپی کن و در ربات تلگرام @polantenbot بفرست. قیمت‌ها و پرداخت داخل ربات است.",
                    fontSize = 13.sp, color = Text2, lineHeight = 20.sp,
                )
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
                ) { Text(if (info == null) "خرید در تلگرام" else "تمدید در تلگرام", color = Accent) }
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

private fun isXiaomi(): Boolean =
    Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

private fun openXiaomiPermissions(context: android.content.Context) {
    val pkg = context.packageName
    val tries = listOf(
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", pkg),
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
            .putExtra("extra_pkgname", pkg),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
    )
    for (i in tries) if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
}

private fun openXiaomiAutostart(context: android.content.Context) {
    val tries = listOf(
        Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )
    for (i in tries) if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
}

private fun countGranted(context: android.content.Context): Int =
    PERMISSIONS.count { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

private fun ignoresBattery(context: android.content.Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
