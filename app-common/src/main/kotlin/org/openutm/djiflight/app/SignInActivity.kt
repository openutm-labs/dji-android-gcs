package org.openutm.djiflight.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.openutm.djiflight.app.ui.Kind
import org.openutm.djiflight.app.ui.Sheet
import org.openutm.djiflight.app.ui.text
import org.openutm.djiflight.app.ui.topBar
import org.openutm.djiflight.app.ui.dp
import org.openutm.djiflight.core.auth.OidcClient
import org.openutm.djiflight.core.auth.PendingSignIn
import org.openutm.djiflight.core.auth.PilotLoginConfig
import org.openutm.djiflight.core.auth.PilotSignIn

/** The signed-in pilot, kept encrypted on the device until it lapses or the pilot signs out. */
class PilotSessions(context: Context, private val now: () -> Long) {
    private val store = SecureStore(context, "pilot")
    private val json = Json { ignoreUnknownKeys = true }
    private val _current = MutableStateFlow(store.read()?.let { runCatching { json.decodeFromString(PilotSignIn.serializer(), it) }.getOrNull() })
    val current: StateFlow<PilotSignIn?> = _current.asStateFlow()

    /** The sign-in, if it still counts now (and is from [issuer], when given). */
    fun valid(issuer: String? = null): PilotSignIn? =
        _current.value?.takeIf { it.valid(now()) && (issuer == null || it.issuer.trimEnd('/') == issuer.trimEnd('/')) }

    fun save(s: PilotSignIn) {
        store.write(json.encodeToString(PilotSignIn.serializer(), s))
        _current.value = s
    }

    fun signOut() {
        store.clear()
        _current.value = null
    }
}

/**
 * Pilot sign-in through the lab's identity server (docs/12, Auth): OIDC authorization code + PKCE in
 * an in-app web view, because DJI's controllers may have no browser to hand off to. The redirect is
 * caught before it loads, so the custom-scheme redirect URI needs no app to open it. Cookies are
 * cleared first, so a shared controller never signs in as the previous pilot.
 */
class SignInActivity : Activity() {
    private val graph get() = (application as FlightApp).graph
    private val ui = MainScope()
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var pending: PendingSignIn? = null
    private var done = false

    private val redirectUri get() = graph.provisioning.value?.pilotLogin?.redirectUri ?: PilotLoginConfig.DEFAULT_REDIRECT_URI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = graph.provisioning.value?.pilotLogin ?: return finish()
        // Not full screen here: a full-screen window doesn't shrink for the keyboard, which then hides the password field.
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.bar)) }
        root.addView(topBar(getString(R.string.signin_title)) { finish() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        status = text(15f, R.color.text2).apply { setPadding(dp(16), dp(4), dp(16), dp(8)); text = getString(R.string.signin_opening, Uri.parse(cfg.issuer).host) }
        root.addView(status)
        setContentView(root)
        // WebView's first start loads Chromium on the main thread (11.7 s on the emulator, seconds on a
        // controller): only once this screen is drawn and focused, so Android never sees an app that
        // ignores input, and the pilot sees the status line meanwhile.
        root.post { root.postDelayed({ if (!isFinishing) openWebView(root, cfg) }, 50) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openWebView(root: LinearLayout, cfg: PilotLoginConfig) {
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = intercept(url)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = intercept(request.url.toString())
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { if (intercept(url)) view.stopLoading() }
                override fun onPageFinished(view: WebView, url: String) { if (!done) status.text = getString(R.string.signin_prompt) }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!done && request.isForMainFrame && !request.url.toString().startsWith(redirectUri)) fail(getString(R.string.signin_page_unreachable, error.description))
                }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        CookieManager.getInstance().removeAllCookies(null)
        val client = OidcClient(graph.transport, cfg)
        ui.launch {
            runCatching { withContext(Dispatchers.IO) { client.start(client.discover(), redirectUri, graph.clock.nowMs()) } }
                .onSuccess { (url, p) -> pending = p; web.loadUrl(url) }
                .onFailure { fail(getString(R.string.signin_server_unreachable, it.message)) }
        }
    }

    private fun intercept(url: String): Boolean {
        if (!url.startsWith(redirectUri)) return false
        if (done) return true
        done = true
        web.visibility = View.INVISIBLE
        status.text = getString(R.string.signin_checking)
        val p = pending ?: return true.also { fail(getString(R.string.signin_answer_early)) }
        val cfg = graph.provisioning.value?.pilotLogin ?: return true.also { finish() }
        ui.launch {
            runCatching { withContext(Dispatchers.IO) { OidcClient(graph.transport, cfg).finish(p, OidcClient.query(Uri.parse(url).encodedQuery), graph.clock.nowMs()) } }
                .onSuccess { s ->
                    graph.pilots.save(s)
                    val name = graph.provisioning.value?.rosterEntry(s)?.name ?: s.name
                    Toast.makeText(this@SignInActivity, getString(R.string.signin_signed_in, name), Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                }
                .onFailure { fail(getString(R.string.signin_not_accepted, it.message)) }
        }
        return true
    }

    private fun fail(msg: String) {
        if (isFinishing) return
        status.text = msg
        Sheet(this).title(getString(R.string.signin_title)).message(msg).button(getString(R.string.signin_close), Kind.PRIMARY) { finish() }.show()
    }


    companion object {
        @Volatile private var warmed = false

        /** Loads WebView's provider off the main thread ahead of time, so Sign in opens faster. */
        fun warmUp(context: Context) {
            if (warmed) return
            warmed = true
            Thread({ runCatching { android.webkit.WebSettings.getDefaultUserAgent(context) } }, "webview-warmup").start()
        }
    }

    override fun onDestroy() {
        ui.cancel()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
