package com.example.pdfreader

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // "Open with" from another app: add to the library and go straight to the reader.
        val external = intent?.data?.takeIf { savedInstanceState == null }?.let { app.library.add(it).uri }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface {
                    var open by rememberSaveable { mutableStateOf(external) }
                    val current = open
                    if (current == null) LibraryScreen(onOpen = { open = it })
                    else key(current) { ReaderScreen(current, onBack = { open = null }) }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        volumeKeyHandler?.invoke(keyCode) == true || super.onKeyDown(keyCode, event)
}
