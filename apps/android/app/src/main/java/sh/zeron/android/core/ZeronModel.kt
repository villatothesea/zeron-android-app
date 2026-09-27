package sh.zeron.android.core

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.zeron.android.BuildConfig
import uniffi.zeron_core.AuthCallback
import uniffi.zeron_core.AuthOrg
import uniffi.zeron_core.ChatConfig
import uniffi.zeron_core.ChatIndicator
import uniffi.zeron_core.ClientEvent
import uniffi.zeron_core.ClientListener
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.CoreConfig
import uniffi.zeron_core.Credentials
import uniffi.zeron_core.DemoFixture
import uniffi.zeron_core.DemoOptions
import uniffi.zeron_core.FaceData
import uniffi.zeron_core.FaceRole
import uniffi.zeron_core.PlatformMeasurer
import uniffi.zeron_core.SandboxLevel
import uniffi.zeron_core.SessionRow
import uniffi.zeron_core.StreamSpeed
import uniffi.zeron_core.TextSystem
import uniffi.zeron_core.TranscriptScale
import uniffi.zeron_core.WallpaperEffect
import uniffi.zeron_core.WorkspaceSnapshot
import uniffi.zeron_core.authExchangeCode
import uniffi.zeron_core.authListOrgs
import uniffi.zeron_core.authProductionEdgeUrl
import uniffi.zeron_core.authRefresh
import uniffi.zeron_core.parseAuthCallback
import uniffi.zeron_core.projectColorIndex
import uniffi.zeron_core.wallpaperRender
import uniffi.zeron_core.wallpaperSafeOpacity
import uniffi.zeron_core.workosAuthorizeUrl
import java.io.File
import java.util.UUID
import android.graphics.Paint
import android.graphics.Typeface

class ZeronModel(app: Application) : AndroidViewModel(app) {
    sealed interface Phase {
        data object Loading : Phase
        data object Ready : Phase
        data object SignedOut : Phase
        data class Failed(val message: String) : Phase
    }

    sealed interface Route {
        data class Folder(val id: String, val title: String) : Route
        data class Session(val id: String) : Route
    }

    enum class Tab { Sessions, Settings, Search }

    var phase by mutableStateOf<Phase>(Phase.Loading)
    var workspace by mutableStateOf<WorkspaceSnapshot?>(null)
    var epoch by mutableIntStateOf(0)
    var appearance by mutableIntStateOf(0)
    var toast by mutableStateOf<String?>(null)
    var tab by mutableStateOf(Tab.Sessions)
    val sessionStack: SnapshotStateList<Route> = mutableStateListOf()
    val settingsStack: SnapshotStateList<Route> = mutableStateListOf()
    var showNewSession by mutableStateOf(false)
    var showSignIn by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var wallpaper by mutableStateOf<Bitmap?>(null)
    var wallpaperOpacity by mutableStateOf(0.42f)
    var wallpaperEffect by mutableStateOf(WallpaperEffect.NONE)
    var signInError by mutableStateOf<String?>(null)
    var signInBusy by mutableStateOf(false)
    var authOrgs by mutableStateOf<List<AuthOrg>?>(null)

    var client: CoreClient? = null
        private set
    var text: TextSystem? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("zeron", 0)
    private var authState: String? = null
    private var pendingExchange: Pair<String, uniffi.zeron_core.AuthTokens>? = null
    private val collapsed = prefs.getStringSet("collapsed", emptySet())?.toMutableSet() ?: mutableSetOf()

    val faces: Map<FaceRole, Typeface> = loadFaces(app)

    init {
        appearance = prefs.getInt("appearance", 0)
        applyNight(appearance, recreate = false)
        wallpaperEffect = effectFrom(prefs.getString("wallpaperEffect", "none"))
        loadWallpaper()
        startDefault()
        watchNetwork()
    }

    fun isCollapsed(id: String): Boolean = collapsed.contains(id)

    fun toggleCollapsed(id: String) {
        if (!collapsed.add(id)) collapsed.remove(id)
        prefs.edit().putStringSet("collapsed", collapsed).apply()
        epoch++
    }

    fun collapseAll() {
        val ws = workspace ?: return
        if (ws.front.pinned.isNotEmpty()) collapsed.add("pinned")
        ws.front.sections.forEach { collapsed.add(it.id) }
        prefs.edit().putStringSet("collapsed", collapsed).apply()
        epoch++
    }

    fun applyAppearance(mode: Int) {
        appearance = mode
        prefs.edit().putInt("appearance", mode).apply()
        applyNight(mode, recreate = true)
        loadWallpaper()
    }

    private fun applyNight(mode: Int, recreate: Boolean) {
        val night = when (mode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (recreate && AppCompatDelegate.getDefaultNightMode() != night) {
            AppCompatDelegate.setDefaultNightMode(night)
        } else if (!recreate) {
            AppCompatDelegate.setDefaultNightMode(night)
        }
    }

    fun showToast(message: String) {
        toast = message
        main.postDelayed({ if (toast == message) toast = null }, 2400)
    }

    fun back(): Boolean {
        if (showNewSession) {
            showNewSession = false
            return true
        }
        if (showSignIn && phase is Phase.Ready) {
            showSignIn = false
            return true
        }
        val stack = if (tab == Tab.Settings) settingsStack else sessionStack
        if (stack.isNotEmpty()) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        return false
    }

    fun openSession(id: String) {
        tab = Tab.Sessions
        showNewSession = false
        sessionStack.clear()
        sessionStack.add(Route.Session(id))
        runCatching { client?.markSeen(id) }
    }

    fun openFolder(id: String, title: String) {
        val stack = if (tab == Tab.Settings) settingsStack else sessionStack
        stack.add(Route.Folder(id, title))
    }

    private fun startDefault() {
        val forced = prefs.getString("boot", null)
        when {
            forced == "signedout" -> {
                phase = Phase.SignedOut
                showSignIn = true
            }
            forced == "demo" || (BuildConfig.DEBUG && prefs.getString("account", null) == null) -> start(demoCredentials(), demo = true)
            prefs.getString("account", null) != null -> start(storedCredentials() ?: demoCredentials(), demo = prefs.getString("account", null) == null)
            else -> {
                phase = Phase.SignedOut
                showSignIn = true
            }
        }
    }

    fun enterDemo() {
        prefs.edit().remove("account").putString("boot", "demo").apply()
        shutdownClient()
        start(demoCredentials(), demo = true)
        showSignIn = false
    }

    fun signOut() {
        shutdownClient()
        prefs.edit().remove("account").putString("boot", "signedout").apply()
        workspace = null
        sessionStack.clear()
        settingsStack.clear()
        phase = Phase.SignedOut
        showSignIn = true
        showToast("Signed out")
    }

    private fun demoCredentials() = Credentials.Demo(
        DemoOptions(
            fixture = DemoFixture.STANDARD,
            transcriptScale = TranscriptScale.Normal,
            streamSpeed = StreamSpeed.REALISTIC,
            longReply = false,
        ),
    )

    private fun start(credentials: Credentials, demo: Boolean) {
        phase = Phase.Loading
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val app = getApplication<Application>()
                val dir = File(app.filesDir, if (demo) "demo" else "core").apply { mkdirs() }
                if (text == null) {
                    val bytes = faces.map { (role, _) ->
                        val name = FACE_FILES.getValue(role)
                        FaceData(role, app.assets.open("fonts/$name").use { it.readBytes() })
                    }
                    text = TextSystem(bytes, AndroidMeasurer(faces))
                }
                val id = prefs.getString("deviceId", null) ?: ("android-" + UUID.randomUUID().toString().take(8)).also {
                    prefs.edit().putString("deviceId", it).apply()
                }
                val config = CoreConfig(
                    edgeUrl = authProductionEdgeUrl(),
                    dataDir = dir.absolutePath,
                    deviceId = id,
                    deviceName = android.os.Build.MODEL ?: "Android",
                    platform = "android",
                    appVersion = BuildConfig.VERSION_NAME ?: "0.2.94",
                )
                val created = CoreClient(config, credentials, object : ClientListener {
                    override fun onEvent(event: ClientEvent) {
                        main.post { onEvent(event) }
                    }
                })
                created.preloadSessions()
                val snap = created.workspace()
                withContext(Dispatchers.Main) {
                    client = created
                    workspace = snap
                    phase = Phase.Ready
                    showSignIn = false
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    phase = Phase.Failed(t.message ?: "Couldn't open the workspace")
                }
            }
        }
    }

    private fun onEvent(event: ClientEvent) {
        val c = client ?: return
        when (event) {
            is ClientEvent.WorkspaceChanged -> workspace = c.workspace()
            is ClientEvent.AuthExpired -> {
                showToast(event.reason)
                signOut()
                return
            }
            else -> Unit
        }
        epoch++
    }

    private fun shutdownClient() {
        runCatching { client?.shutdown() }
        runCatching { client?.close() }
        client = null
    }

    fun refreshPull() {
        client?.onForeground()
        workspace = client?.workspace()
        epoch++
    }

    fun liveCounts(): Pair<Int, Int> {
        val ws = workspace ?: return 0 to 0
        val rows = buildList {
            addAll(ws.front.pinned)
            ws.front.sections.forEach { addAll(it.sessions) }
            addAll(ws.front.recent)
        }
        val seen = HashSet<String>()
        var working = 0
        var awaiting = 0
        for (row in rows) {
            if (!seen.add(row.id)) continue
            if (row.indicator == ChatIndicator.WORKING) working++
            if (row.indicator == ChatIndicator.AWAITING_INPUT) awaiting++
        }
        return working to awaiting
    }

    fun sessionsIn(id: String): List<SessionRow> {
        val ws = workspace ?: return emptyList()
        return when (id) {
            "pinned" -> ws.front.pinned
            "archived" -> ws.archived
            "recent" -> ws.front.recent
            else -> ws.front.sections.firstOrNull { it.id == id }?.sessions ?: emptyList()
        }
    }

    fun search(query: String): List<SessionRow> {
        val c = client ?: return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return workspace?.front?.recent ?: emptyList()
        return c.search(q, 60u).map { it.session }
    }

    fun pin(id: String, pinned: Boolean) = attempt { if (pinned) it.pinSession(id) else it.unpinSession(id) }
    fun archive(id: String) {
        attempt { it.archiveSession(id) }
        showToast("Archived")
    }
    fun unarchive(id: String) = attempt { it.unarchiveSession(id) }
    fun rename(id: String, title: String) = attempt { it.renameSession(id, title) }
    fun move(id: String, section: String?) = attempt { it.assignSection(id, section) }
    fun createSection(name: String) = attempt { it.createSection(name) }
    fun renameSection(id: String, name: String) = attempt { it.renameSection(id, name) }
    fun deleteSection(id: String) = attempt { it.deleteSection(id) }

    private fun attempt(body: (CoreClient) -> Unit) {
        val c = client ?: return
        try {
            body(c)
        } catch (t: Throwable) {
            showToast(t.message ?: "Couldn't update")
        }
    }

    fun homeColorIndex(): Int = projectColorIndex("home").toInt()

    // ── auth ──────────────────────────────────────────────────────────────

    fun authorizeUrl(): String {
        val state = UUID.randomUUID().toString()
        authState = state
        return workosAuthorizeUrl(state)
    }

    fun completeAuth(url: String) {
        val callback = parseAuthCallback(url) ?: run {
            signInError = "Sign-in didn't complete. Try again."
            return
        }
        when (callback) {
            is AuthCallback.Error -> signInError = callback.description ?: callback.error
            is AuthCallback.Code -> {
                if (authState != null && callback.state != null && callback.state != authState) {
                    signInError = "Sign-in didn't complete. Try again."
                    return
                }
                viewModelScope.launch {
                    signInBusy = true
                    signInError = null
                    try {
                        val edge = authProductionEdgeUrl()
                        val exchange = authExchangeCode(edge, callback.code)
                        val orgs = authListOrgs(edge, exchange.tokens.accessToken)
                        if (orgs.isEmpty()) {
                            signInError = "This account isn't in an organization yet."
                        } else if (orgs.size == 1) {
                            finishOrg(exchange.user.id, orgs[0], exchange.tokens.refreshToken)
                        } else {
                            pendingExchange = exchange.user.id to exchange.tokens
                            authOrgs = orgs
                        }
                    } catch (t: Throwable) {
                        signInError = t.message ?: "Sign-in didn't complete."
                    } finally {
                        signInBusy = false
                    }
                }
            }
        }
    }

    fun chooseOrg(org: AuthOrg) {
        val pending = pendingExchange ?: return
        authOrgs = null
        viewModelScope.launch {
            signInBusy = true
            try {
                finishOrg(pending.first, org, pending.second.refreshToken)
            } catch (t: Throwable) {
                signInError = t.message ?: "Couldn't join that organization."
            } finally {
                signInBusy = false
            }
        }
    }

    private suspend fun finishOrg(userId: String, org: AuthOrg, refresh: String) {
        val edge = authProductionEdgeUrl()
        val tokens = authRefresh(edge, refresh, org.organizationId)
        prefs.edit()
            .putString("account", "$userId\n${org.organizationId}\n${tokens.accessToken}\n${tokens.refreshToken}")
            .putString("boot", "account")
            .apply()
        withContext(Dispatchers.Main) {
            shutdownClient()
            authOrgs = null
        }
        start(
            Credentials.WorkOs(userId, org.organizationId, tokens),
            demo = false,
        )
    }

    private fun storedCredentials(): Credentials? {
        val raw = prefs.getString("account", null) ?: return null
        val parts = raw.split('\n')
        if (parts.size < 4) return null
        return Credentials.WorkOs(
            parts[0],
            parts[1],
            uniffi.zeron_core.AuthTokens(parts[2], parts[3]),
        )
    }

    // ── wallpaper ─────────────────────────────────────────────────────────

    fun setWallpaper(bytes: ByteArray, name: String) {
        val dir = File(getApplication<Application>().filesDir, "wallpaper").apply { mkdirs() }
        File(dir, "source.jpg").writeBytes(bytes)
        prefs.edit().putString("wallpaperName", name).apply()
        loadWallpaper()
    }

    fun clearWallpaper() {
        File(getApplication<Application>().filesDir, "wallpaper/source.jpg").delete()
        wallpaper = null
    }

    fun applyWallpaperEffect(effect: WallpaperEffect) {
        wallpaperEffect = effect
        prefs.edit().putString("wallpaperEffect", effectKey(effect)).apply()
        loadWallpaper()
    }

    private fun loadWallpaper() {
        val file = File(getApplication<Application>().filesDir, "wallpaper/source.jpg")
        if (!file.exists()) {
            wallpaper = null
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            val src = BitmapFactory.decodeFile(file.absolutePath) ?: return@launch
            val max = 900
            val scale = minOf(1f, max / maxOf(src.width, src.height).toFloat())
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            val scaled = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val rgba = ByteArray(w * h * 4)
            for (i in pixels.indices) {
                val p = pixels[i]
                rgba[i * 4] = ((p shr 16) and 0xFF).toByte()
                rgba[i * 4 + 1] = ((p shr 8) and 0xFF).toByte()
                rgba[i * 4 + 2] = (p and 0xFF).toByte()
                rgba[i * 4 + 3] = ((p shr 24) and 0xFF).toByte()
            }
            val dark = appearance == 2 || (appearance == 0 && isNight())
            val rendered = wallpaperRender(rgba, w.toUInt(), h.toUInt(), wallpaperEffect, !dark)
            val text = if (dark) 0xE8E8EAu else 0x27272Cu
            val bg = if (dark) 0x060606u else 0xF3F3F5u
            val opacity = wallpaperSafeOpacity(rendered, w.toUInt(), h.toUInt(), text, bg, 0.35f, 4.5f, 0.55f)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val argb = IntArray(w * h)
            for (i in argb.indices) {
                val r = rendered[i * 4].toInt() and 0xFF
                val g = rendered[i * 4 + 1].toInt() and 0xFF
                val b = rendered[i * 4 + 2].toInt() and 0xFF
                val a = rendered[i * 4 + 3].toInt() and 0xFF
                argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            out.setPixels(argb, 0, w, 0, 0, w, h)
            withContext(Dispatchers.Main) {
                wallpaper = out
                wallpaperOpacity = opacity
            }
        }
    }

    private fun isNight(): Boolean {
        val mode = getApplication<Application>().resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun wallpaperName(): String? = prefs.getString("wallpaperName", null)

    fun defaultConfig(harness: String, model: String?, effort: String?) = ChatConfig(
        harness = harness,
        model = model,
        reasoning = effort,
        modelOptions = emptyMap(),
        sandbox = SandboxLevel.WORKSPACE_WRITE,
    )

    private fun watchNetwork() {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { client?.setNetworkOnline(true) }
            }
            override fun onLost(network: Network) {
                main.post { client?.setNetworkOnline(false) }
            }
        })
    }

    fun onForeground() { client?.onForeground() }
    fun onBackground() { client?.onBackground() }

    /** Screenshot / deep-link routes, analogous to the iOS `-route` argument. */
    fun applyLaunch(route: String?, chat: String?, theme: String?, query: String?) {
        when (theme) {
            "dark" -> applyAppearance(2)
            "light" -> applyAppearance(1)
            "system" -> applyAppearance(0)
        }
        when (route) {
            "settings", "more" -> tab = Tab.Settings
            "search" -> {
                tab = Tab.Search
                if (!query.isNullOrEmpty()) searchQuery = query
            }
            "signin", "signedout" -> {
                signOut()
            }
            "new" -> showNewSession = true
            "session" -> if (!chat.isNullOrEmpty()) openSession(chat)
        }
    }

    companion object {
        val FACE_FILES = mapOf(
            FaceRole.SANS to "Geist.ttf",
            FaceRole.SANS_MEDIUM to "Geist-Medium.ttf",
            FaceRole.SANS_SEMIBOLD to "Geist-SemiBold.ttf",
            FaceRole.SANS_BOLD to "Geist-Bold.ttf",
            FaceRole.SANS_ITALIC to "Geist-Italic.ttf",
            FaceRole.SANS_MEDIUM_ITALIC to "Geist-MediumItalic.ttf",
            FaceRole.SANS_SEMIBOLD_ITALIC to "Geist-SemiBoldItalic.ttf",
            FaceRole.SANS_BOLD_ITALIC to "Geist-BoldItalic.ttf",
            FaceRole.MONO to "GeistMono.ttf",
            FaceRole.MONO_MEDIUM to "GeistMono-Medium.ttf",
            FaceRole.MONO_SEMIBOLD to "GeistMono-SemiBold.ttf",
            FaceRole.MONO_ITALIC to "GeistMono-Italic.ttf",
        )

        fun loadFaces(app: Application): Map<FaceRole, Typeface> = FACE_FILES.mapValues { (_, name) ->
            Typeface.createFromAsset(app.assets, "fonts/$name")
        }

        fun effectKey(effect: WallpaperEffect) = when (effect) {
            WallpaperEffect.NONE -> "none"
            WallpaperEffect.DITHER -> "dither"
            WallpaperEffect.ASCII -> "ascii"
            WallpaperEffect.HALFTONE -> "halftone"
            WallpaperEffect.SCANLINES -> "scanlines"
        }

        fun effectFrom(key: String?) = when (key) {
            "dither" -> WallpaperEffect.DITHER
            "ascii" -> WallpaperEffect.ASCII
            "halftone" -> WallpaperEffect.HALFTONE
            "scanlines" -> WallpaperEffect.SCANLINES
            else -> WallpaperEffect.NONE
        }

        fun effectLabel(effect: WallpaperEffect) = when (effect) {
            WallpaperEffect.NONE -> "None"
            WallpaperEffect.DITHER -> "Dither"
            WallpaperEffect.ASCII -> "ASCII"
            WallpaperEffect.HALFTONE -> "Halftone"
            WallpaperEffect.SCANLINES -> "Scanlines"
        }
    }
}

/** CoreText stand-in: widths in the same point space Rust measures with. */
private class AndroidMeasurer(private val faces: Map<FaceRole, Typeface>) : PlatformMeasurer {
    private val paints = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG) }

    override fun measure(face: FaceRole, size: Float, ligatures: Boolean, text: String): Float {
        val paint = paint(face, size, ligatures)
        return paint.measureText(text)
    }

    override fun measureRun(face: FaceRole, size: Float, ligatures: Boolean, text: String): List<Float> {
        val paint = paint(face, size, ligatures)
        val out = ArrayList<Float>(text.length)
        var i = 0
        while (i < text.length) {
            val count = Character.charCount(text.codePointAt(i))
            out.add(paint.measureText(text, i, i + count))
            i += count
        }
        return out
    }

    private fun paint(face: FaceRole, size: Float, ligatures: Boolean): Paint {
        val paint = paints.get()!!
        paint.typeface = faces[face] ?: faces[FaceRole.SANS]
        paint.textSize = size
        paint.fontFeatureSettings = if (ligatures) "\"liga\" 1, \"calt\" 1" else "\"liga\" 0, \"calt\" 0"
        return paint
    }
}
