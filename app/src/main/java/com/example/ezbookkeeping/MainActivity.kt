package com.example.ezbookkeeping

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.example.ezbookkeeping.databinding.ActivityMainBinding
import android.webkit.ValueCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.app.DownloadManager
import android.webkit.URLUtil
import android.os.Environment
import android.webkit.CookieManager
import android.util.Base64
import java.io.File
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefsName = "EzBookkeepingPrefs"
    private val urlKey = "savedUrl"
    private var viewTheme: String? = null
    private var homeUrl: String? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    companion object {
        private const val PERMISSION_REQUEST_CODE = 123
    }
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uris = result.data?.data?.let { arrayOf(it) }
            filePathCallback?.onReceiveValue(uris)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isSystemDark = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        viewTheme = if (isSystemDark) "dark" else "light"
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val sharedPreferences = getSharedPreferences(prefsName, MODE_PRIVATE)
        val savedUrl = sharedPreferences.getString(urlKey, null)

        if (savedUrl != null) {
            setupAndLoadWebView(savedUrl)
        } else {
            setupUrlInput()
        }

        handleStatusBarIcons(resources.configuration)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })

        checkAndRequestPermissions()
    }


    private fun setupUrlInput() {
        binding.urlInputContainer.visibility = View.VISIBLE
        binding.webView.visibility = View.GONE

        binding.enterButton.setOnClickListener {
            var url = binding.urlEditText.text.toString().trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                val sharedPreferences = getSharedPreferences(prefsName, MODE_PRIVATE)
                sharedPreferences.edit {
                    putString(urlKey, url)
                }
                setupAndLoadWebView(url)
            } else {
                binding.urlInputLayout.error = "网址不能为空"
            }
        }
    }

    private fun setupAndLoadWebView(url: String) {
        binding.urlInputContainer.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        homeUrl = url
        setupWebViewSettings()
        binding.webView.loadUrl(url)
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleStatusBarIcons(newConfig)
        if (binding.webView.url != null) {
            binding.webView.reload()
        }
    }

    private fun setupWebViewSettings() {
        binding.webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")
        binding.webView.addJavascriptInterface(BlobDownloader(this), "AndroidBlob")

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setGeolocationEnabled(true)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(binding.webView.settings, true)
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback = filePathCallback
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                fileChooserLauncher.launch(intent)
                return true
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(getThemeObserverScript(), null)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                view?.loadUrl(url)
                return true
            }
        }
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            if (url.startsWith("blob")) {

                val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)

                val js = """
                        (async function() {
                            const blob = await fetch("$url").then(r => r.blob());
                            const reader = new FileReader();
                            reader.onloadend = function() {
                                const base64 = reader.result.split(',')[1];
                                AndroidBlob.downloadBase64(base64, "$filename");
                            };
                            reader.readAsDataURL(blob);
                        })();
                        """

                binding.webView.evaluateJavascript(js, null)
                return@setDownloadListener
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                // --- 这是我们之前处理普通下载的代码，保持不变 ---
                val request = DownloadManager.Request(Uri.parse(url))
                val cookies = CookieManager.getInstance().getCookie(url)
                request.addRequestHeader("cookie", cookies)
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription("正在下载文件...")
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimetype)
                )
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)

                // (可选) 给用户一个提示
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, "开始下载...", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.webView.setOnLongClickListener { true }
    }
    private fun getThemeObserverScript(): String {
        // 关键常量：localStorage 键名
        val settingsKey = "ebk_app_settings"

        // 注入的 JavaScript 代码
        val script = """
    (function() {
        // 确保 AndroidBridge 接口可用
        if (typeof window.AndroidBridge === 'undefined' || typeof window.AndroidBridge.updateTheme !== 'function') {
            console.warn("AndroidBridge not found. Theme monitoring aborted.");
            return;
        }

        // 引入局部状态变量来追踪上次报告的主题
        let lastTheme = null; 
        
        // 监控间隔时间（毫秒）
        const POLLING_INTERVAL = 1000; 

        function checkThemeChange() {
            let currentTheme = 'system';
            
            try {
                const storageData = localStorage.getItem('$settingsKey');
                if (!storageData || storageData === '{}') {
                    currentTheme = 'auto'; 
                } else {
                    const settings = JSON.parse(storageData);
                    currentTheme = settings.theme || 'auto'; 
                }
            } catch (error) {
                console.error("Error reading theme from localStorage:", error);
                return; 
            }

            if (currentTheme !== lastTheme) {
                lastTheme = currentTheme;
                window.AndroidBridge.updateTheme(currentTheme);
            }
        }
        checkThemeChange();
        if (window.themeMonitorInterval) {
            clearInterval(window.themeMonitorInterval);
        }
        window.themeMonitorInterval = setInterval(checkThemeChange, POLLING_INTERVAL);
        window.addEventListener('beforeunload', function() {
            clearInterval(window.themeMonitorInterval);
        });
    })();
    """.trimIndent()
        return script
    }
    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        } else {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun handleStatusBarIcons(config: Configuration) {
        val nightModeFlags = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDarkMode
        controller.isAppearanceLightNavigationBars = !isDarkMode
    }

    inner class WebAppInterface {
        @android.webkit.JavascriptInterface
        fun updateTheme(theme: String) {
            val normalizedTheme = theme.lowercase()

            if (normalizedTheme == viewTheme) {
                return
            }
            runOnUiThread {
                val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                val isSystemDark = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

                val isDarkMode = when (theme.lowercase()) {
                    "dark" -> true
                    "light" -> false
                    else -> isSystemDark
                }

                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(binding.webView.settings, isDarkMode)
                }

                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !isDarkMode
                controller.isAppearanceLightNavigationBars = !isDarkMode

                if (homeUrl != null) {
                    binding.webView.loadUrl(homeUrl!!)
                } else {
                    binding.webView.reload()
                }
                viewTheme = normalizedTheme
            }
        }
    }
    inner class BlobDownloader(val context: Context) {

        @android.webkit.JavascriptInterface
        fun downloadBase64(base64Data: String, filename: String) {
            try {
                val fileBytes = Base64.decode(base64Data, Base64.DEFAULT)
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    filename
                )
                file.writeBytes(fileBytes)
                (context as Activity).runOnUiThread {
                    android.widget.Toast.makeText(context, "下载完成: $filename", android.widget.Toast.LENGTH_LONG).show()
                }
                MediaScannerConnection.scanFile(context, arrayOf(file.toString()), null, null)

            } catch (e: Exception) {
                e.printStackTrace()
                (context as Activity).runOnUiThread {
                    android.widget.Toast.makeText(context, "下载失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}