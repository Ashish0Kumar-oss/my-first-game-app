package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.game.GameEngine
import com.example.game.SoundManager
import com.example.game.GameDataStore
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.scale
import kotlinx.coroutines.launch
import kotlin.math.abs

// Ancient Emerald & Golden Ruins Vibe Colors
val JungleDark = Color(0xFF0C1611)
val TempleGold = Color(0xFFFFD700)
val GoldOrange = Color(0xFFFF8C00)
val MossGreen = Color(0xFF00A86B)
val LightMoss = Color(0xFF20E090)
val CharcoalGray = Color(0xFF232B28)
val ObsidianTranslucent = Color(0xD9121916)

@Composable
fun GameUI(
    gameEngine: GameEngine,
    soundManager: SoundManager,
    modifier: Modifier = Modifier,
    onToggleSound: (Boolean) -> Unit,
    onToggleMusic: (Boolean) -> Unit,
    isSoundEnabled: Boolean,
    isMusicEnabled: Boolean,
    onRenderSurface: @Composable (Modifier) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf("MENU") } // MENU, PLAYING, SETTINGS, SHOP, LEADERBOARD
    
    // Periodically sync UI screen status with GameEngine state
    LaunchedEffect(gameEngine.gameState) {
        when (gameEngine.gameState) {
            GameEngine.State.MENU -> currentScreen = "MENU"
            GameEngine.State.RUNNING -> currentScreen = "PLAYING"
            GameEngine.State.PAUSED -> currentScreen = "PLAYING" // overlay pause dialog
            GameEngine.State.GAME_OVER -> currentScreen = "PLAYING" // overlay gameover dialog
        }
    }

    Box(modifier = modifier.fillMaxSize().background(JungleDark)) {
        // 1. OpenGL 3D surface view below overlays
        onRenderSurface(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var dragX = 0f
                    var dragY = 0f
                    detectDragGestures(
                        onDragStart = {
                            dragX = 0f
                            dragY = 0f
                        },
                        onDragEnd = {
                            val minSwipeThreshold = 65f
                            if (abs(dragX) > abs(dragY)) {
                                if (abs(dragX) > minSwipeThreshold) {
                                    if (dragX > 0) gameEngine.swipeRight() else gameEngine.swipeLeft()
                                }
                            } else {
                                if (abs(dragY) > minSwipeThreshold) {
                                    if (dragY > 0) gameEngine.swipeDown() else gameEngine.swipeUp()
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragX += dragAmount.x
                            dragY += dragAmount.y
                        }
                    )
                }
        )

        // 2. Play screen dynamic HUD overlays (drawn on top of 3D scene)
        if (currentScreen == "PLAYING") {
            GameHUD(
                gameEngine = gameEngine,
                onPauseClick = {
                    soundManager.playSlideSound()
                    gameEngine.pauseGame()
                }
            )

            // Overlays over active screen
            if (gameEngine.gameState == GameEngine.State.PAUSED) {
                PauseDialog(
                    onResume = { gameEngine.resumeGame() },
                    onRestart = { gameEngine.startGame() },
                    onQuit = {
                        gameEngine.quitToMenu()
                        currentScreen = "MENU"
                    }
                )
            }

            if (gameEngine.gameState == GameEngine.State.GAME_OVER) {
                GameOverDialog(
                    gameEngine = gameEngine,
                    onRestart = { gameEngine.startGame() },
                    onQuit = {
                        gameEngine.quitToMenu()
                        currentScreen = "MENU"
                    }
                )
            }
        }

        // 3. Independent Screen Overlays
        AnimatedVisibility(
            visible = currentScreen == "MENU",
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            MainMenuOverlay(
                gameEngine = gameEngine,
                onPlayClick = {
                    soundManager.playCoinSound()
                    gameEngine.startGame()
                },
                onSettingsClick = {
                    soundManager.playSlideSound()
                    currentScreen = "SETTINGS"
                },
                onShopClick = {
                    soundManager.playSlideSound()
                    currentScreen = "SHOP"
                },
                onLeaderboardClick = {
                    soundManager.playSlideSound()
                    currentScreen = "LEADERBOARD"
                }
            )
        }

        AnimatedVisibility(
            visible = currentScreen == "SETTINGS",
            enter = fadeIn() + expandIn(),
            exit = fadeOut() + shrinkOut()
        ) {
            SettingsOverlay(
                isSoundEnabled = isSoundEnabled,
                isMusicEnabled = isMusicEnabled,
                onToggleSound = onToggleSound,
                onToggleMusic = onToggleMusic,
                onBack = {
                    soundManager.playSlideSound()
                    currentScreen = "MENU"
                }
            )
        }

        AnimatedVisibility(
            visible = currentScreen == "SHOP",
            enter = fadeIn() + slideInHorizontally(),
            exit = fadeOut() + slideOutHorizontally()
        ) {
            ShopOverlay(
                gameEngine = gameEngine,
                soundManager = soundManager,
                onBack = {
                    soundManager.playSlideSound()
                    currentScreen = "MENU"
                }
            )
        }

        AnimatedVisibility(
            visible = currentScreen == "LEADERBOARD",
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            LeaderboardOverlay(
                gameEngine = gameEngine,
                onBack = {
                    soundManager.playSlideSound()
                    currentScreen = "MENU"
                }
            )
        }
    }
}

// --- SUBVIEWS ---

@Composable
fun GameHUD(
    gameEngine: GameEngine,
    onPauseClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        // TOP HUD BAR
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Stats (Distance + Coins)
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianTranslucent)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DirectionsRun,
                        contentDescription = "Distance",
                        tint = MossGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${gameEngine.distanceRan.toInt()} m",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.MonetizationOn,
                        contentDescription = "Coins",
                        tint = TempleGold,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${gameEngine.currentRunCoins}",
                        color = TempleGold,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        modifier = Modifier.testTag("hud_coins_count")
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (i in 1..3) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "Life $i",
                            tint = if (i <= gameEngine.playerHealth) Color.Red else Color(0xFF444444),
                            modifier = Modifier.size(18.dp)
                        )
                        if (i < 3) Spacer(modifier = Modifier.width(4.dp))
                    }
                }
            }

            // High Score Banner
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianTranslucent)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "High: ${gameEngine.highScore}",
                    color = TempleGold,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Pause Trigger
            IconButton(
                onClick = onPauseClick,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ObsidianTranslucent)
                    .testTag("pause_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = "Pause Game",
                    tint = Color.White
                )
            }
        }

        // SWIPE HELPER HINT OVERLAY (Fades out when running)
        if (gameEngine.distanceRan < 20f) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianTranslucent)
                    .padding(16.dp)
            ) {
                Text(
                    text = "Swipe to Control!\n← LEFT  |  RIGHT →\n↑ JUMP  |  SLIDE ↓",
                    color = Color.White,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Light,
                    lineHeight = 22.sp
                )
            }
        }

        // POWER UPS COUNTDOWN INDICATORS
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 32.dp, start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (pu in GameEngine.PowerUp.values()) {
                val rem = gameEngine.getPowerUpTimeRemaining(pu)
                if (rem > 0f) {
                    PowerUpProgressBar(powerUp = pu, secondsLeft = rem)
                }
            }
        }
    }
}

@Composable
fun PowerUpProgressBar(powerUp: GameEngine.PowerUp, secondsLeft: Float) {
    val maxDuration = 15f // approximation for progress bar scaling
    val progress = (secondsLeft / maxDuration).coerceIn(0f, 1f)
    
    val color = when (powerUp) {
        GameEngine.PowerUp.MAGNET -> Color(0xFF33B5E5)
        GameEngine.PowerUp.SHIELD -> Color(0xFF99CC00)
        GameEngine.PowerUp.INVINCIBLE -> Color(0xFFFFBB33)
        GameEngine.PowerUp.SPEED -> Color(0xFFFF4444)
        GameEngine.PowerUp.JUMP -> Color(0xFFAA66CC)
        GameEngine.PowerUp.SLOW_MO -> Color(0xFF00DDFF)
        GameEngine.PowerUp.DOUBLE_COINS -> Color(0xFFFF8800)
    }

    Column(modifier = Modifier.width(140.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = powerUp.name,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "%.1fs".format(secondsLeft),
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = Color.DarkGray
        )
    }
}

@Composable
fun MainMenuOverlay(
    gameEngine: GameEngine,
    onPlayClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onShopClick: () -> Unit,
    onLeaderboardClick: () -> Unit
) {
    // Beautiful ancient dynamic emerald backdrop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, JungleDark)
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Shiny Animated Temple Title
            val infiniteTransition = rememberInfiniteTransition(label = "title")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.96f,
                targetValue = 1.04f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1500, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            Text(
                text = "TEMPLE ESCAPE",
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Serif,
                color = TempleGold,
                textAlign = TextAlign.Center,
                modifier = Modifier.scale(scale)
            )
            Text(
                text = "3D RUNNER",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MossGreen,
                letterSpacing = 6.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(50.dp))

            // Play Button
            Button(
                onClick = onPlayClick,
                colors = ButtonDefaults.buttonColors(containerColor = MossGreen),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .width(220.dp)
                    .height(54.dp)
                    .border(2.dp, TempleGold, RoundedCornerShape(24.dp))
                    .testTag("play_button")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "START RUN",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Shop / Skins Button
            Button(
                onClick = onShopClick,
                colors = ButtonDefaults.buttonColors(containerColor = CharcoalGray),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .width(200.dp)
                    .height(48.dp)
                    .testTag("shop_button")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storefront, contentDescription = null, tint = TempleGold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "CHARACTER SHOP", color = Color.White, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Leaderboard Button
            Button(
                onClick = onLeaderboardClick,
                colors = ButtonDefaults.buttonColors(containerColor = CharcoalGray),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .width(200.dp)
                    .height(48.dp)
                    .testTag("leaderboard_button")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Leaderboard, contentDescription = null, tint = MossGreen)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "LEADERBOARD", color = Color.White, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Settings Button
            Button(
                onClick = onSettingsClick,
                colors = ButtonDefaults.buttonColors(containerColor = CharcoalGray),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .width(200.dp)
                    .height(48.dp)
                    .testTag("settings_button")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "SETTINGS", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        // FOOTER TEXT
        Text(
            text = "Ancient Temple Escape © 2026",
            color = Color.Gray,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}

@Composable
fun SettingsOverlay(
    isSoundEnabled: Boolean,
    isMusicEnabled: Boolean,
    onToggleSound: (Boolean) -> Unit,
    onToggleMusic: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var showPrivacy by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianTranslucent)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .clip(RoundedCornerShape(24.dp))
                .background(JungleDark)
                .border(1.dp, MossGreen, RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SETTINGS",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TempleGold
            )
            Spacer(modifier = Modifier.height(24.dp))

            // Sound FX Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sound Effects", color = Color.White, fontSize = 16.sp)
                Switch(
                    checked = isSoundEnabled,
                    onCheckedChange = onToggleSound,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TempleGold,
                        checkedTrackColor = MossGreen
                    ),
                    modifier = Modifier.testTag("sound_toggle")
                )
            }

            // Music Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ambient Music", color = Color.White, fontSize = 16.sp)
                Switch(
                    checked = isMusicEnabled,
                    onCheckedChange = onToggleMusic,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TempleGold,
                        checkedTrackColor = MossGreen
                    ),
                    modifier = Modifier.testTag("music_toggle")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // About Button
            Button(
                onClick = { showAbout = true },
                colors = ButtonDefaults.buttonColors(containerColor = CharcoalGray),
                modifier = Modifier.fillMaxWidth().testTag("about_button")
            ) {
                Text("About", color = Color.White)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Privacy Policy Button
            Button(
                onClick = { showPrivacy = true },
                colors = ButtonDefaults.buttonColors(containerColor = CharcoalGray),
                modifier = Modifier.fillMaxWidth().testTag("privacy_button")
            ) {
                Text("Privacy Policy", color = Color.White)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Close/Back Button
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = MossGreen),
                modifier = Modifier.width(150.dp)
            ) {
                Text("BACK TO MENU", color = Color.White)
            }
        }
    }

    // Modal dialogs
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("About Temple Escape 3D", color = TempleGold) },
            text = {
                Text(
                    "You are a daring explorer trapped in an ancient jungle temple. Run continuously to escape the giant demonic beast chasing you. Drag to slide, jump, and dodge dynamic spike traps, logs, swinging axes, and rolling stones!\n\nBuilt using Native Android SDK, Kotlin, Jetpack Compose, and OpenGL ES 3.0.",
                    color = Color.White
                )
            },
            confirmButton = {
                Button(onClick = { showAbout = false }) { Text("Dismiss") }
            },
            containerColor = JungleDark
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("Privacy Policy", color = TempleGold) },
            text = {
                Text(
                    "Temple Escape 3D respect your privacy. No personal identifier data, tracking profiles, or physical geolocation are ever transmitted to external servers. All coins, high scores, levels, and progress parameters are saved locally on your device via standard Jetpack DataStore encryption systems.",
                    color = Color.White
                )
            },
            confirmButton = {
                Button(onClick = { showPrivacy = false }) { Text("Close") }
            },
            containerColor = JungleDark
        )
    }
}

@Composable
fun ShopOverlay(
    gameEngine: GameEngine,
    soundManager: SoundManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var totalCoins by remember { mutableStateOf(0) }
    var unlockedGoldenSkin by remember { mutableStateOf(false) }
    var equippedGoldenSkin by remember { mutableStateOf(gameEngine.isGoldenSkinEquipped) }
    var showErrorMessage by remember { mutableStateOf(false) }

    // Read coins and skins states on load
    LaunchedEffect(Unit) {
        totalCoins = gameEngine.coinsCollected
        val store = gameEngine.isGoldenSkinEquipped
        unlockedGoldenSkin = GameDataStore(context).unlockedGoldenSkinFlow.first()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianTranslucent)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .clip(RoundedCornerShape(24.dp))
                .background(JungleDark)
                .border(1.dp, TempleGold, RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("CHARACTER SHOP", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TempleGold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MonetizationOn, contentDescription = null, tint = TempleGold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("$totalCoins", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Standard Explorer (Unlocked)
            Card(
                colors = CardDefaults.cardColors(containerColor = CharcoalGray),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Daring Adventurer", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Default character outfit.", color = Color.Gray, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            gameEngine.equipGoldenSkin(false)
                            equippedGoldenSkin = false
                            soundManager.playCoinSound()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!equippedGoldenSkin) MossGreen else Color.DarkGray
                        )
                    ) {
                        Text(if (!equippedGoldenSkin) "EQUIPPED" else "EQUIP")
                    }
                }
            }

            // Golden Skin (Locked/Purchasable)
            Card(
                colors = CardDefaults.cardColors(containerColor = CharcoalGray),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .border(
                        if (equippedGoldenSkin) 2.dp else 0.dp,
                        TempleGold,
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Solid Gold Skin", color = TempleGold, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.Star, contentDescription = null, tint = TempleGold, modifier = Modifier.size(16.dp))
                        }
                        Text("Glows bright in jungle ruins.", color = Color.LightGray, fontSize = 12.sp)
                    }

                    if (unlockedGoldenSkin) {
                        Button(
                            onClick = {
                                gameEngine.equipGoldenSkin(true)
                                equippedGoldenSkin = true
                                soundManager.playCoinSound()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (equippedGoldenSkin) MossGreen else Color.DarkGray
                            )
                        ) {
                            Text(if (equippedGoldenSkin) "EQUIPPED" else "EQUIP")
                        }
                    } else {
                        Button(
                            onClick = {
                                gameEngine.purchaseGoldenSkin(
                                    onSuccess = {
                                        unlockedGoldenSkin = true
                                        equippedGoldenSkin = true
                                        totalCoins = gameEngine.coinsCollected
                                        soundManager.playCoinSound()
                                    },
                                    onFailure = {
                                        showErrorMessage = true
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldOrange)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.MonetizationOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("300 COINS", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Error popup
            if (showErrorMessage) {
                Text(
                    "NOT ENOUGH COINS! Run more to collect gold.",
                    color = Color.Red,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = MossGreen),
                modifier = Modifier.width(150.dp)
            ) {
                Text("DONE", color = Color.White)
            }
        }
    }
}

@Composable
fun LeaderboardOverlay(
    gameEngine: GameEngine,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianTranslucent)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .clip(RoundedCornerShape(24.dp))
                .background(JungleDark)
                .border(1.dp, MossGreen, RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("TEMPLE LEADERBOARD", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TempleGold)
            Spacer(modifier = Modifier.height(16.dp))

            // Dynamic user high score
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CharcoalGray)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("1st", fontWeight = FontWeight.Black, color = TempleGold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("You (Adventure Star)", color = Color.White, fontSize = 16.sp)
                }
                Text("${gameEngine.highScore}", color = TempleGold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mock milestones competitors
            val competitors = listOf(
                Pair("Lara_R", 4200),
                Pair("Indy_Jones", 3850),
                Pair("TempleMaster", 2900),
                Pair("JungleRunner99", 1500)
            )

            competitors.forEachIndexed { idx, pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${idx + 2}nd  ${pair.first}", color = Color.LightGray)
                    Text("${pair.second}", color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = MossGreen),
                modifier = Modifier.width(150.dp)
            ) {
                Text("BACK", color = Color.White)
            }
        }
    }
}

@Composable
fun PauseDialog(
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onQuit: () -> Unit
) {
    Dialog(onDismissRequest = onResume) {
        Card(
            colors = CardDefaults.cardColors(containerColor = JungleDark),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .border(2.dp, MossGreen, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "RUN PAUSED",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = TempleGold
                )
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(containerColor = MossGreen),
                    modifier = Modifier.fillMaxWidth().testTag("resume_button")
                ) {
                    Text("RESUME", color = Color.White)
                }
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onRestart,
                    colors = ButtonDefaults.buttonColors(containerColor = CharcoalGray),
                    modifier = Modifier.fillMaxWidth().testTag("restart_button")
                ) {
                    Text("RESTART RUN", color = Color.White)
                }
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onQuit,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    modifier = Modifier.fillMaxWidth().testTag("quit_button")
                ) {
                    Text("QUIT TO MENU", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun GameOverDialog(
    gameEngine: GameEngine,
    onRestart: () -> Unit,
    onQuit: () -> Unit
) {
    Dialog(onDismissRequest = {}) {
        Card(
            colors = CardDefaults.cardColors(containerColor = JungleDark),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .border(2.dp, Color.Red, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Dangerous,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = gameEngine.deathCause,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.Red,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Stats
                Text(
                    text = "Distance: ${gameEngine.distanceRan.toInt()} meters",
                    color = Color.White,
                    fontSize = 18.sp
                )
                Text(
                    text = "Gold coins: ${gameEngine.currentRunCoins}",
                    color = TempleGold,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "High Score: ${gameEngine.highScore}",
                    color = MossGreen,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onRestart,
                    colors = ButtonDefaults.buttonColors(containerColor = MossGreen),
                    modifier = Modifier.fillMaxWidth().testTag("game_over_restart_button")
                ) {
                    Text("RUN AGAIN", color = Color.White)
                }
                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onQuit,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    modifier = Modifier.fillMaxWidth().testTag("game_over_quit_button")
                ) {
                    Text("BACK TO MENU", color = Color.White)
                }
            }
        }
    }
}
