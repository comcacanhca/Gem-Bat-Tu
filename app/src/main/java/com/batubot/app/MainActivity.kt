package com.batubot.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.batubot.app.analytics.AnalyticsHelper
import com.batubot.app.analytics.LocalAnalyticsHelper
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.batubot.app.ui.screens.SystemAccuracyScreen
import com.batubot.app.ui.screens.LogViewerScreen
import com.batubot.app.data.BaTuHistoryEntry
import com.batubot.app.data.ChatPreferences
import com.batubot.app.ui.components.MenuOptionFAB
import com.batubot.app.ui.screens.*
import com.batubot.app.ui.theme.ChatbotTheme
import com.batubot.app.utils.PromptProvider
import com.batubot.app.utils.SkillManager
import com.batubot.app.data.GeminiApiService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.launch
import java.util.Calendar
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.batubot.app.workers.AdviceForecastWorker
import com.batubot.app.workers.DailyTaskWorker
import java.util.concurrent.TimeUnit
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission

import com.batubot.app.data.AppSessionManager
import com.batubot.app.data.GeminiChatSession
import com.batubot.app.data.GeminiDestination

const val GEMINI_URL_NGHIENCUU ="https://gemini.google.com/u/1/gem/59c09a1dab24"
const val GEMINI_URL_UNGDUNG ="https://gemini.google.com/u/1/gem/b1bdad11cedd"


const val BROWSER_TAB_ID ="com.batubot.app.GEMINI_TAB"

enum class Screen {
    Main, YourBazi, Lifelong, Conversion, History, Destiny, Principles, Daily, GeminiChat, SystemAccuracy, LogViewer
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var analyticsHelper: AnalyticsHelper

    private var geminiTabOpened = false
    private var startChat = false
    private var mInterstitialAd: InterstitialAd? = null

    private var navigateToDaily by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("navigate_to") == "daily") {
            navigateToDaily = true
        }
    }

    private lateinit var appUpdateManager: AppUpdateManager
    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            android.util.Log.e("Update", "Update flow failed! Result code: ${result.resultCode}")
        }
    }

    private fun checkForUpdates() {
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo

        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    updateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    updateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                )
            }
        }
    }

    private var mRewardedAd: com.google.android.gms.ads.rewarded.RewardedAd? = null

    private fun loadRewardedAd() {
        android.util.Log.d("AdDebug", "loadRewardedAd called")
        val adRequest = AdRequest.Builder().build()
        com.google.android.gms.ads.rewarded.RewardedAd.load(
            this,
            "ca-app-pub-8425103950864579/8454933378",
            adRequest,
            object : com.google.android.gms.ads.rewarded.RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    android.util.Log.e("AdDebug", "RewardedAd failed to load: ${adError.message}")
                    mRewardedAd = null
                }
                override fun onAdLoaded(rewardedAd: com.google.android.gms.ads.rewarded.RewardedAd) {
                    android.util.Log.d("AdDebug", "RewardedAd loaded successfully")
                    mRewardedAd = rewardedAd
                }
            }
        )
    }

    private fun showRewardedAd(onRewardEarned: () -> Unit) {
        android.util.Log.d("AdDebug", "showRewardedAd called. mRewardedAd: $mRewardedAd")
        if (mRewardedAd != null) {
            mRewardedAd?.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    android.util.Log.d("AdDebug", "RewardedAd dismissed")
                    mRewardedAd = null
                    loadRewardedAd()
                }
                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    android.util.Log.e("AdDebug", "RewardedAd failed to show: ${adError.message}")
                    mRewardedAd = null
                    loadRewardedAd()
                }
            }
            mRewardedAd?.show(this) { rewardItem ->
                android.util.Log.d("AdDebug", "User earned reward: ${rewardItem.amount} ${rewardItem.type}")
                onRewardEarned()
            }
        } else {
            android.util.Log.w("AdDebug", "RewardedAd not ready, reloading and granting direct fallback")
            loadRewardedAd()
            Toast.makeText(this, "Quảng cáo phần thưởng chưa sẵn sàng, đã cộng thưởng trực tiếp!", Toast.LENGTH_SHORT).show()
            onRewardEarned()
        }
    }

    private fun loadInterstitialAd() {
        android.util.Log.d("AdDebug", "loadInterstitialAd called")
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            this,
            "ca-app-pub-8425103950864579/6957130515",
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    android.util.Log.e("AdDebug", "Ad failed to load: ${adError.message}")
                    mInterstitialAd = null
                }

                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    android.util.Log.d("AdDebug", "Ad loaded successfully")
                    mInterstitialAd = interstitialAd
                }
            })
    }

    private fun showInterstitialAd(onAdDismissed: () -> Unit) {
        android.util.Log.d("AdDebug", "showInterstitialAd called. mInterstitialAd: $mInterstitialAd")
        if (mInterstitialAd != null) {
            mInterstitialAd?.fullScreenContentCallback =
                object : com.google.android.gms.ads.FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        android.util.Log.d("AdDebug", "Ad dismissed")
                        mInterstitialAd = null
                        // Preload the next ad after one is shown
                        loadInterstitialAd()
                        onAdDismissed()
                    }

                    override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                        android.util.Log.e("AdDebug", "Ad failed to show: ${adError.message}")
                        mInterstitialAd = null
                        // Try to load again if it failed to show
                        loadInterstitialAd()
                        onAdDismissed()
                    }
                }
            mInterstitialAd?.show(this)
        } else {
            android.util.Log.w("AdDebug", "Ad not ready, loading and proceeding")
            loadInterstitialAd()
            onAdDismissed()
        }
    }

    private fun openGemini(context: Context, initialText: String? = null, strings: AppStrings, gemMode: String = "UNGDUNG") {
        lifecycleScope.launch(Dispatchers.IO) {
            val relevantSkill = if (!initialText.isNullOrBlank()) {
                val fullContext = initialText
                val hasCachCuc = fullContext.contains("Cách cục", ignoreCase = true) || fullContext.contains("cách cục", ignoreCase = true)
                val hasDungThan = fullContext.contains("Dụng thần", ignoreCase = true) || fullContext.contains("dụng thần", ignoreCase = true)
                val hasHyThan = fullContext.contains("Hỷ thần", ignoreCase = true) || fullContext.contains("hỷ thần", ignoreCase = true)
                val isCachCucKnown = hasCachCuc && (hasDungThan || hasHyThan)
                SkillManager.getSkillForButtonTopic(context, initialText, isCachCucKnown = isCachCucKnown)
            } else {
                ""
            }

            val combinedInstruction = GeminiApiService.buildFullSystemInstruction(relevantSkill)
            val fullContextText = if (!initialText.isNullOrBlank()) {
                """
                $combinedInstruction

                --- YÊU CẦU CỦA NGƯỜI DÙNG ---
                $initialText
                """.trimIndent()
            } else {
                combinedInstruction
            }

            withContext(Dispatchers.Main) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("BaTu", fullContextText))
                Toast.makeText(context, strings.copyGeminiSuccess, Toast.LENGTH_SHORT).show()

                // 1. Thử mở App Gemini chính thức trên thiết bị: com.google.android.apps.bard
                val geminiAppPackage = "com.google.android.apps.bard"
                val appLaunchIntent = context.packageManager.getLaunchIntentForPackage(geminiAppPackage)
                if (appLaunchIntent != null) {
                    appLaunchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(appLaunchIntent)
                        geminiTabOpened = true
                        return@withContext
                    } catch (_: Exception) {
                        // Fallback to web
                    }
                }

                // 2. Fallback: Mở trang web Gemini mặc định (https://gemini.google.com/) trên trình duyệt
                val targetUrl = "https://gemini.google.com/"
                val uri = targetUrl.toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // Lấy package trình duyệt mặc định của thiết bị
                val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://"))
                val resolveInfo = context.packageManager.resolveActivity(browserIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                val defaultBrowserPackage = resolveInfo?.activityInfo?.packageName
                if (defaultBrowserPackage != null) {
                    intent.setPackage(defaultBrowserPackage)
                }

                try {
                    context.startActivity(intent)
                    geminiTabOpened = true
                } catch (e: Exception) {
                    try {
                        val fallbackIntent = Intent(Intent.ACTION_VIEW, targetUrl.toUri()).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(fallbackIntent)
                        geminiTabOpened = true
                    } catch (e2: Exception) {
                        Toast.makeText(context, strings.errorOpenLink, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            scheduleDailyTaskCheck(6, "DailyTaskReminderMorning")
            scheduleDailyTaskCheck(17, "DailyTaskReminderEvening")
            scheduleAdviceForecastNotification()
        }
    }

    private fun scheduleDailyTaskCheck(hour: Int, uniqueName: String) {
        val workManager = WorkManager.getInstance(this)
        workManager.cancelUniqueWork(uniqueName) // Hủy công việc cũ cùng tên trước khi lập lịch lại
        
        val currentTime = Calendar.getInstance()
        val targetTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }

        if (targetTime.before(currentTime)) {
            targetTime.add(Calendar.DAY_OF_MONTH, 1)
        }

        val initialDelay = targetTime.timeInMillis - currentTime.timeInMillis

        val dailyWorkRequest = PeriodicWorkRequestBuilder<DailyTaskWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .addTag(uniqueName)
            .build()

        Log.d("MainActivity", "Đã lập lịch $uniqueName (delay $initialDelay ms, lặp 24h)")
        workManager.enqueueUniquePeriodicWork(
            uniqueName,
            ExistingPeriodicWorkPolicy.KEEP,
            dailyWorkRequest
        )
    }

    private fun scheduleAdviceForecastNotification() {
        val workManager = WorkManager.getInstance(this)
        workManager.cancelUniqueWork(AdviceForecastWorker.UNIQUE_WORK_NAME)

        val currentTime = Calendar.getInstance()
        val targetTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 4)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }

        if (targetTime.before(currentTime)) {
            targetTime.add(Calendar.DAY_OF_MONTH, 1)
        }

        val initialDelay = targetTime.timeInMillis - currentTime.timeInMillis

        val dailyWorkRequest = PeriodicWorkRequestBuilder<AdviceForecastWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .addTag(AdviceForecastWorker.UNIQUE_WORK_NAME)
            .build()

        Log.d("MainActivity", "Đã lập lịch ${AdviceForecastWorker.UNIQUE_WORK_NAME} (delay $initialDelay ms, lặp 24h)")
        workManager.enqueueUniquePeriodicWork(
            AdviceForecastWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            dailyWorkRequest
        )
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            System.setProperty("debug.hwui.renderer", "opengl")
        }
        super.onCreate(savedInstanceState)
        appUpdateManager = AppUpdateManagerFactory.create(this)
        checkForUpdates()
        startChat = false
        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "EdgeToEdge failed", e)
        }
        MobileAds.initialize(this) {
            loadInterstitialAd()
            loadRewardedAd()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d("MainActivity", "Requesting POST_NOTIFICATIONS permission")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                scheduleDailyTaskCheck(6, "DailyTaskReminderMorning")
                scheduleDailyTaskCheck(17, "DailyTaskReminderEvening")
                scheduleAdviceForecastNotification()
                Log.d("MainActivity", "Enqueued daily notification work requests")
            }
        } else {
            scheduleDailyTaskCheck(6, "DailyTaskReminderMorning")
            scheduleDailyTaskCheck(17, "DailyTaskReminderEvening")
            scheduleAdviceForecastNotification()
            Log.d("MainActivity", "Enqueued daily notification work requests")
        }

        val preferences = ChatPreferences(applicationContext)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val showSecondaryIcons = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

            val darkThemeState by preferences.darkThemeFlow.collectAsState(initial = isSystemInDarkTheme())
            val languageState by preferences.languageFlow.collectAsState(initial = "vi")

            ChatbotTheme(darkTheme = darkThemeState) {
                CompositionLocalProvider(LocalAnalyticsHelper provides analyticsHelper) {
                    ProvideStrings(language = languageState) {
                    ProvideThongDiep(language = languageState) {
                        val strings = LocalStrings.current
                        val thongDiep = ThongDiepStrings.current
                        val userBazi by preferences.userBaziFlow.collectAsState(initial = null)
                    val scope = rememberCoroutineScope()
                    val context = LocalContext.current
                    val clipboardManager = LocalClipboardManager.current

                    // Bộ nhớ tạm riêng cho màn hình LifelongScreen
                    var currentBazi by remember { mutableStateOf<BaTuHistoryEntry?>(null) }

                    val skipGeminiTutorial by preferences.skipGeminiTutorialFlow.collectAsState(initial = false)
                    val promptCountState by preferences.promptCountFlow.collectAsState(initial = 0)

                    val hasShownWelcomeState by preferences.hasShownWelcomeFlow.collectAsState(initial = null)
                    var showWelcomeScreen by remember { mutableStateOf(false) }

                    LaunchedEffect(hasShownWelcomeState) {
                        showWelcomeScreen = (hasShownWelcomeState == false)
                    }

                    var currentScreen by rememberSaveable { mutableStateOf(Screen.Main) }
                    var previousScreen by rememberSaveable { mutableStateOf(Screen.Main) }
                    val saveableStateHolder = rememberSaveableStateHolder()

                    LaunchedEffect(currentScreen) {
                        analyticsHelper.logScreenView(currentScreen.name)
                    }

                    LaunchedEffect(intent, navigateToDaily) {
                        if (intent.getStringExtra("navigate_to") == "daily" || navigateToDaily) {
                            currentScreen = Screen.Daily
                            navigateToDaily = false
                        }
                    }

                    var showMenu by remember { mutableStateOf(false) }
                    var gemMode by remember { mutableStateOf("UNGDUNG") }

                    // Xử lý nút back
                    BackHandler(enabled = currentScreen != Screen.Main || showMenu) {
                        if (showMenu) {
                            showMenu = false
                        } else if (currentScreen == Screen.GeminiChat) {
                            currentScreen = if (previousScreen != Screen.GeminiChat) previousScreen else Screen.Main
                        } else {
                            currentScreen = Screen.Main
                        }
                    }

                    // Hộp thoại lịch sử, tra ngược và nhập thông tin
                    var showBaTuDialog by remember { mutableStateOf(false) }

                    var promptCount by remember { mutableIntStateOf(0) }

                    // Hộp thoại xác nhận mở Gemini Web cũ
                    var showGeminiConfirm by remember { mutableStateOf(false) }
                    var pendingGeminiPrompt by remember { mutableStateOf<String?>(null) }

                    // Trạng thái cho Gemini Chat Screen và Lựa chọn Session
                    var chatInitialPrompt by remember { mutableStateOf<String?>(null) }
                    var chatInitialPromptIsFeatureScreen by remember { mutableStateOf(false) }
                    var showGeminiChoiceDialog by remember { mutableStateOf(false) }
                    var pendingPromptForChoice by remember { mutableStateOf<String?>(null) }

                    val openWebProceed: (String?) -> Unit = { prompt ->
                        if (skipGeminiTutorial) {
                            openGemini(context, prompt, strings, gemMode)
                        } else {
                            pendingGeminiPrompt = prompt
                            showGeminiConfirm = true
                        }
                    }

                    val openChatInAppProceed: (String?, Boolean) -> Unit = { prompt, fromFeatureScreen ->
                        chatInitialPrompt = prompt
                        chatInitialPromptIsFeatureScreen = fromFeatureScreen
                        if (currentScreen != Screen.GeminiChat) {
                            previousScreen = currentScreen
                        }
                        currentScreen = Screen.GeminiChat
                    }

                    val onGoToGeminiWithConfirm: (String?, Boolean) -> Unit = { prompt, fromFeatureScreen ->
                        promptCount++
                        val proceed = {
                            openChatInAppProceed(prompt, fromFeatureScreen)
                        }
                        if (promptCount % 15 == 0) {
                            showInterstitialAd { proceed() }
                        } else {
                            proceed()
                        }
                    }

                    if (showWelcomeScreen) {
                        WelcomeScreen(strings = strings) {
                            scope.launch {
                                preferences.saveHasShownWelcome(true)
                            }
                            showWelcomeScreen = false
                        }
                    } else {
                        var fabOverlayTopPx by remember { mutableFloatStateOf(0f) }
                        
                        LaunchedEffect(currentScreen) {
                            if (currentScreen != Screen.GeminiChat) {
                                fabOverlayTopPx = 0f
                                Log.d("MainActivity", "Switched away from GeminiChat, resetting fabOverlayTopPx to 0f")
                            }
                        }

                        // Khung chính của màn hình (Scaffold)
                        Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = MaterialTheme.colorScheme.background,
                        topBar = { },
                        floatingActionButton = {
                            val density = LocalDensity.current
                            var fabBottomPx by remember { mutableFloatStateOf(0f) }
                            val isInChat = currentScreen == Screen.GeminiChat
                            val marginPx = with(density) { 12.dp.toPx() }
                            val targetShiftPx = if (isInChat && fabOverlayTopPx > 0f && fabBottomPx > 0f) {
                                (fabBottomPx - fabOverlayTopPx + marginPx).coerceAtLeast(0f)
                            } else {
                                0f
                            }
                            val fabBottomShift by animateDpAsState(
                                targetValue = with(density) { targetShiftPx.toDp() },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "FabGeminiShiftAnim"
                            )
                            val fabBottomShiftClamped = if (fabBottomShift < 0.dp) {
                                Log.d("MainActivity", "Spring overshoot produced negative fab padding: $fabBottomShift, clamped to 0.dp")
                                0.dp
                            } else {
                                fabBottomShift
                            }

                            // Các nút hành động nổi (FloatingActionButtons)
                            Box(
                                modifier = Modifier.onGloballyPositioned { coords ->
                                    val measuredFabBottom = coords.boundsInRoot().bottom
                                    if (fabBottomPx != measuredFabBottom) {
                                        Log.d("MainActivity", "fabNaturalBottomPx: $measuredFabBottom")
                                        fabBottomPx = measuredFabBottom
                                    }
                                }
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    modifier = Modifier.padding(bottom = fabBottomShiftClamped)
                                ) {
                                    val fabAlpha = if (isInChat) 0.7f else 1f

                                    // Nút Gemini AI riêng biệt đặt ở trên icon menu
                                    val isGeminiGenerating = GeminiChatSession.isGenerating
                                    FloatingActionButton(
                                        onClick = {
                                            if (currentScreen == Screen.GeminiChat) {
                                                currentScreen = if (previousScreen != Screen.GeminiChat) previousScreen else Screen.Main
                                            } else {
                                                if (currentScreen != Screen.GeminiChat) {
                                                    previousScreen = currentScreen
                                                }
                                                currentScreen = Screen.GeminiChat
                                            }
                                            showMenu = false
                                        },
                                        modifier = Modifier.padding(bottom = 8.dp),
                                        containerColor = if (currentScreen == Screen.GeminiChat)
                                            MaterialTheme.colorScheme.primary.copy(alpha = fabAlpha)
                                        else
                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = fabAlpha)
                                    ) {
                                        if (isGeminiGenerating) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = if (currentScreen == Screen.GeminiChat)
                                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = fabAlpha)
                                                else
                                                    MaterialTheme.colorScheme.onSecondaryContainer,
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.AutoAwesome,
                                                contentDescription = strings.geminiChatTitle,
                                                tint = if (currentScreen == Screen.GeminiChat)
                                                    MaterialTheme.colorScheme.onPrimary.copy(alpha = fabAlpha)
                                                else
                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }

                                    // Nút chuyển đổi chế độ Sáng/Tối
                                    FloatingActionButton(
                                        onClick = {
                                            scope.launch {
                                                preferences.saveDarkTheme(!darkThemeState)
                                            }
                                        },
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = fabAlpha)
                                    ) {
                                        Icon(
                                            if (darkThemeState) Icons.Default.LightMode else Icons.Default.DarkMode,
                                            contentDescription = strings.toggleTheme,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = fabAlpha)
                                        )
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                        Crossfade(targetState = currentScreen, label = "screenTransition") { screen ->
                            saveableStateHolder.SaveableStateProvider(key = screen) {
                                when (screen) {
                                    Screen.Main -> MainContent(
                                        innerPadding = innerPadding,
                                        userBazi = userBazi,
                                        onShowBaTuDialog = { showBaTuDialog = true },
                                        onOpenGemini = { 
                                            geminiTabOpened = false
                                            onGoToGeminiWithConfirm(null, false) 
                                        },
                                        onNavigateToScreen = { currentScreen = it },
                                        onOpenTuVi = {
                                            val intent = Intent(
                                                Intent.ACTION_VIEW,
                                                "https://tuvibacphai.com/tuvilyso/".toUri()
                                            )
                                            context.startActivity(intent)
                                        },
                                        strings = strings,
                                        showSecondaryIcons = showSecondaryIcons,
                                        clipboardManager = clipboardManager,
                                        context = context,
                                        gemMode = gemMode,
                                        onGemModeChange = { gemMode = it },
                                        onShowRewardedAd = { onReward -> showRewardedAd(onReward) }
                                    )
                                    Screen.YourBazi -> {
                                        if (mInterstitialAd == null) loadInterstitialAd()
                                        YourBaziScreen(
                                            innerPadding = innerPadding,
                                            userBazi = userBazi,
                                            onSaveBazi = { entry ->
                                                scope.launch {
                                                    // Lưu trực tiếp vào UserBazi để đảm bảo Dụng thần, Cách cục được cập nhật ngay lập tức
                                                    preferences.saveUserBazi(entry)
                                                    // Đồng thời cập nhật vào lịch sử để đồng bộ dữ liệu tra cứu
                                                    preferences.saveToHistory(entry)
                                                }
                                            },
                                            onGoToGemini = { prompt -> onGoToGeminiWithConfirm(prompt, true) },
                                            onShowAd = { onDone -> showInterstitialAd(onDone) },
                                            onNavigateToDaily = { currentScreen = Screen.Daily },
                                            strings = strings,
                                            preferences = preferences
                                        )
                                    }
                                    Screen.Lifelong -> LifelongScreen(
                                        innerPadding = innerPadding,
                                        userBazi = currentBazi ?: userBazi,
                                        onSaveBazi = { entry ->
                                            scope.launch {
                                                val savedEntry = preferences.saveToHistory(entry)
                                                currentBazi = savedEntry
                                            }
                                        },
                                        onGoToGemini = { basePrompt ->
                                            val fullPrompt = PromptProvider.getLifelongPrompt(basePrompt)
                                            onGoToGeminiWithConfirm(fullPrompt, true)
                                        },
                                        onShowAd = { onDone -> showInterstitialAd(onDone) },
                                        onShowRewardedAd = { onReward -> showRewardedAd(onReward) },
                                        strings = strings,
                                        preferences = preferences
                                    )
                                    Screen.Conversion -> ConversionScreen(
                                        innerPadding = innerPadding,
                                        showAd = { onDone -> showInterstitialAd(onDone) },
                                        strings = strings,
                                        context = context,
                                        clipboardManager = clipboardManager,
                                        preferences = preferences,
                                        externalScope = scope,
                                        onNavigateToLifelong = { entry ->
                                            currentBazi = entry
                                            currentScreen = Screen.Lifelong
                                        }
                                    )
                                    Screen.History -> HistoryScreen(
                                        innerPadding = innerPadding,
                                        preferences = preferences,
                                        strings = strings,
                                        context = context,
                                        clipboardManager = clipboardManager,
                                        onNavigateToYourBazi = { entry ->
                                            scope.launch {
                                                preferences.saveUserBazi(entry)
                                                currentScreen = Screen.YourBazi
                                            }
                                        },
                                        onNavigateToLifelong = { entry ->
                                            currentBazi = entry
                                            currentScreen = Screen.Lifelong
                                        }
                                    )
                                    Screen.Destiny -> DestinyScreen(innerPadding,
                                        strings = strings,
                                        onNavigateToPrinciples = { currentScreen = Screen.Principles })
                                    Screen.Principles -> PrinciplesScreen(innerPadding,
                                        strings = strings,
                                        onBack = { currentScreen = Screen.Destiny })
                                    Screen.Daily -> DailyScreen(
                                        innerPadding = innerPadding,
                                        userBazi = userBazi,
                                        preferences = preferences,
                                        strings = strings,
                                        onShowAd = { onDone -> showInterstitialAd(onDone) },
                                        onNavigateBack = { currentScreen = Screen.Main }
                                    )
                                    Screen.SystemAccuracy -> SystemAccuracyScreen(
                                        context = context,
                                        onBack = { currentScreen = Screen.Main },
                                        onOpenLogViewer = { currentScreen = Screen.LogViewer }
                                    )
                                    Screen.LogViewer -> LogViewerScreen(
                                        context = context,
                                        onBack = { currentScreen = Screen.SystemAccuracy }
                                    )
                                    Screen.GeminiChat -> GeminiChatScreen(
                                        innerPadding = innerPadding,
                                        initialPrompt = chatInitialPrompt,
                                        initialPromptIsFeatureScreen = chatInitialPromptIsFeatureScreen,
                                        onClearInitialPrompt = { chatInitialPrompt = null },
                                        preferences = preferences,
                                        strings = strings,
                                        onShowRewardedAd = { onReward -> showRewardedAd(onReward) },
                                        onNavigateBack = {
                                            currentScreen = if (previousScreen != Screen.GeminiChat) previousScreen else Screen.Main
                                        },
                                        onOpenWebGemini = { webPrompt ->
                                            openGemini(context, webPrompt, strings, gemMode)
                                        },
                                        onFabOverlayTopChange = { topPx ->
                                            fabOverlayTopPx = topPx
                                        }
                                    )
                                }
                            }
                        }

                        if (showBaTuDialog) {
                            BaTuInputDialog(
                                onDismiss = { showBaTuDialog = false },
                                onGoToGemini = { prompt ->
                                    onGoToGeminiWithConfirm(prompt, true)
                                },
                                showAd = { onDone -> showInterstitialAd(onDone) },
                                preferences = preferences,
                                externalScope = scope,
                                onResultCalculated = { entry ->
                                    scope.launch {
                                        preferences.saveUserBazi(entry)
                                        preferences.saveToHistory(entry)
                                    }
                                    showBaTuDialog = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
}
}
}
