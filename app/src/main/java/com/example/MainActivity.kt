package com.example

import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.game.GameDataStore
import com.example.game.GameEngine
import com.example.game.SoundManager
import com.example.opengl.GameRenderer
import com.example.ui.GameUI
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var dataStore: GameDataStore
    private lateinit var soundManager: SoundManager
    private lateinit var gameEngine: GameEngine
    private lateinit var gameRenderer: GameRenderer
    private var glSurfaceView: GLSurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize core managers
        dataStore = GameDataStore(applicationContext)
        soundManager = SoundManager()
        gameEngine = GameEngine(applicationContext, dataStore, soundManager)
        gameRenderer = GameRenderer(applicationContext, gameEngine)

        // 2. Render UI
        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Observe persistent preferences
                    val isSoundEnabled by dataStore.soundEnabledFlow.collectAsState(initial = true)
                    val isMusicEnabled by dataStore.musicEnabledFlow.collectAsState(initial = true)
                    val scope = rememberCoroutineScope()

                    // Synchronize settings with SoundManager
                    LaunchedEffect(isSoundEnabled) {
                        soundManager.setSoundEnabled(isSoundEnabled)
                    }
                    LaunchedEffect(isMusicEnabled) {
                        soundManager.setMusicEnabled(isMusicEnabled)
                        if (isMusicEnabled) {
                            soundManager.startBackgroundMusic()
                        } else {
                            soundManager.stopBackgroundMusic()
                        }
                    }

                    GameUI(
                        gameEngine = gameEngine,
                        soundManager = soundManager,
                        isSoundEnabled = isSoundEnabled,
                        isMusicEnabled = isMusicEnabled,
                        onToggleSound = { enabled ->
                            scope.launch { dataStore.setSoundEnabled(enabled) }
                        },
                        onToggleMusic = { enabled ->
                            scope.launch { dataStore.setMusicEnabled(enabled) }
                        },
                        onRenderSurface = { modifier ->
                            AndroidView(
                                factory = { ctx ->
                                    GLSurfaceView(ctx).apply {
                                        setEGLContextClientVersion(3) // Request GLES 3.0 context
                                        setRenderer(gameRenderer)
                                        glSurfaceView = this
                                    }
                                },
                                modifier = modifier
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView?.onResume()
        soundManager.startBackgroundMusic()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
        gameEngine.pauseGame()
        soundManager.stopBackgroundMusic()
    }

    override fun onDestroy() {
        super.onDestroy()
        gameEngine.onDestroy()
        soundManager.onDestroy()
    }
}
