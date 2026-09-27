package sh.zeron.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import sh.zeron.android.core.ZeronModel
import sh.zeron.android.ui.ZeronApp

class MainActivity : ComponentActivity() {
    private val model: ZeronModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { ZeronApp(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        model.onForeground()
    }

    override fun onStop() {
        model.onBackground()
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString
        if (data != null && data.startsWith("zeron://")) model.completeAuth(data)
        val route = intent?.getStringExtra("route") ?: return
        model.applyLaunch(route, intent.getStringExtra("chat"), intent.getStringExtra("theme"), intent.getStringExtra("query"))
    }
}
