package com.example.dougasort

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root() }
            }
        }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val vm: MainViewModel = viewModel()
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    // "All files access" is granted on a Settings screen, so re-check whenever we come back.
    var allFiles by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allFiles = Environment.isExternalStorageManager() }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(permission) }
    LaunchedEffect(granted) { if (granted) vm.refresh() }

    when {
        !granted -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("動画へのアクセス許可が必要です")
        }
        !allFiles -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "「✓済」ボタンで動画を確認なしにアルバムへ移動・復元するため、\n" +
                    "「全ファイルへのアクセス」の許可が必要です。\n\n" +
                    "次の画面で DougaSort をオンにして、戻ってきてください。",
            )
            Button(
                modifier = Modifier.padding(top = 16.dp),
                onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:${ctx.packageName}")),
                    )
                },
            ) { Text("設定を開く") }
        }
        else -> AppScreen(vm)
    }
}
