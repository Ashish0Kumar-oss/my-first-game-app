package com.example.game

import android.content.Context
import com.example.opengl.GameRenderer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class GameEngine(
    private val context: Context,
    private val dataStore: GameDataStore,
    private val soundManager: SoundManager
) {
    // Game States
    enum class State { MENU, RUNNING, PAUSED, GAME_OVER }

    var gameState = State.MENU
        private set

    // Speed configurations
    private val baseSpeed = 6.0f
    private val maxSpeed = 15.0f
    var runSpeed = baseSpeed
        private set
    var runTime = 0f
        private set

    // Lives / Health system
    var playerHealth = 3
        private set
    var deathCause = "CAUGHT BY THE GUARDIAN BEAST!"
        private set

    // Distances
    var distanceRan = 0f
        private set
    var coinsCollected = 0
        private set
    var currentRunCoins = 0
        private set
    var highScore = 0
        private set

    // Screen Shake effect
    var screenShakeAmount = 0f
        private set

    // Player position
    var playerX = 0f // Interpolated actual X
    var playerY = 0f // Jump height Y
    var targetLane = 0 // -1 (Left), 0 (Center), 1 (Right)
    private val laneWidth = 1.6f

    // Motion rates
    private var jumpVelocity = 0f
    private val gravity = -35f
    private val initJumpSpeed = 13f
    var landingParticlesTrigger = 0
        private set

    // Action timers
    var slideTimer = 0f
        private set
    private val maxSlideDuration = 0.8f
    val isSliding: Boolean get() = slideTimer > 0f
    val isJumping: Boolean get() = playerY > 0f

    // Guardian Monster state
    var monsterDistance = 5.0f // meters behind player. If <= 0, game over.
        private set
    private val safeMonsterDistance = 5.0f
    private var monsterCatchUpTimer = 0f

    // Collection sparks trigger for rendering
    val coinSparksToSpawn = mutableListOf<FloatArray>()

    // Active power-ups & cooldowns
    enum class PowerUp { MAGNET, SHIELD, INVINCIBLE, SPEED, JUMP, SLOW_MO, DOUBLE_COINS }
    private val activePowerUps = mutableMapOf<PowerUp, Float>()

    fun getPowerUpTimeRemaining(powerUp: PowerUp): Float = activePowerUps[powerUp] ?: 0f
    fun isPowerUpActive(powerUp: PowerUp): Boolean = (activePowerUps[powerUp] ?: 0f) > 0f

    // Level procedural generation & Object Pools
    class Coin(var x: Float, var y: Float, var z: Float, val value: Int, var collected: Boolean = false, var magnetAttracted: Boolean = false)
    
    enum class ObstacleType {
        BOULDER, FIRE_TRAP, SPIKES, SWINGING_AXE, TEMPLE_DOOR, COLLAPSED_BRIDGE, LOG, ROTATING_HAMMER, LOW_CEILING
    }
    class Obstacle(
        val type: ObstacleType,
        val x: Float,
        val y: Float,
        val z: Float,
        val lane: Int,
        val width: Float = 1.0f,
        val height: Float = 1.0f,
        var animPhase: Float = 0f,
        var passed: Boolean = false
    )

    class Segment(
        val zStart: Float,
        val zEnd: Float,
        val segmentId: Int,
        val coins: MutableList<Coin> = mutableListOf(),
        val obstacles: MutableList<Obstacle> = mutableListOf()
    )

    val activeSegments = mutableListOf<Segment>()
    private val segmentLength = 25.0f
    private var lastSegmentZ = 0f
    private var segmentCounter = 0

    // Character Skins
    var isGoldenSkinEquipped = false

    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        engineScope.launch {
            highScore = dataStore.highScoreFlow.first()
            val savedCoins = dataStore.totalCoinsFlow.first()
            isGoldenSkinEquipped = dataStore.unlockedGoldenSkinFlow.first()
        }
    }

    fun startGame() {
        distanceRan = 0f
        currentRunCoins = 0
        runSpeed = baseSpeed
        runTime = 0f
        playerHealth = 3
        deathCause = "CAUGHT BY THE GUARDIAN BEAST!"
        playerX = 0f
        playerY = 0f
        targetLane = 0
        jumpVelocity = 0f
        slideTimer = 0f
        monsterDistance = safeMonsterDistance
        monsterCatchUpTimer = 0f
        activePowerUps.clear()
        synchronized(coinSparksToSpawn) {
            coinSparksToSpawn.clear()
        }

        // Clear and rebuild segments
        activeSegments.clear()
        segmentCounter = 0
        lastSegmentZ = 0f

        // Initial empty segments to let player settle
        for (i in 0..2) {
            spawnSegment(empty = true)
        }
        // Spawn actual challenge segments
        for (i in 3..7) {
            spawnSegment(empty = false)
        }

        gameState = State.RUNNING
        soundManager.playMonsterRoarSound()
    }

    fun pauseGame() {
        if (gameState == State.RUNNING) {
            gameState = State.PAUSED
        }
    }

    fun resumeGame() {
        if (gameState == State.PAUSED) {
            gameState = State.RUNNING
        }
    }

    fun quitToMenu() {
        gameState = State.MENU
    }

    // Input swipe mapping
    fun swipeLeft() {
        if (gameState != State.RUNNING) return
        if (targetLane > -1) {
            targetLane--
            soundManager.playSlideSound()
        }
    }

    fun swipeRight() {
        if (gameState != State.RUNNING) return
        if (targetLane < 1) {
            targetLane++
            soundManager.playSlideSound()
        }
    }

    fun swipeUp() {
        if (gameState != State.RUNNING) return
        if (!isJumping && !isSliding) {
            val boost = if (isPowerUpActive(PowerUp.JUMP)) 1.4f else 1.0f
            jumpVelocity = initJumpSpeed * boost
            playerY = 0.01f // Trigger jump
            soundManager.playJumpSound()
        }
    }

    fun swipeDown() {
        if (gameState != State.RUNNING) return
        if (!isJumping) {
            slideTimer = maxSlideDuration
            soundManager.playSlideSound()
        } else {
            // Fast fall-through dive!
            jumpVelocity = -initJumpSpeed
        }
    }

    // Core simulation tick (delta time)
    fun update(dt: Float) {
        if (gameState != State.RUNNING) return

        // 1. Slow Motion and Speed Boost effects
        var effectiveDt = dt
        if (isPowerUpActive(PowerUp.SLOW_MO)) {
            effectiveDt *= 0.5f
        }

        var speedMultiplier = 1.0f
        if (isPowerUpActive(PowerUp.SPEED)) {
            speedMultiplier = 1.6f
        }
        
        // Track running time and scale speed smoothly (+1.0 m/s every 15s)
        runTime += effectiveDt
        val rawSpeed = baseSpeed + (runTime / 15f) * 1.0f
        runSpeed = rawSpeed.coerceIn(baseSpeed, maxSpeed) * speedMultiplier

        // Increment distance
        val step = runSpeed * effectiveDt
        distanceRan += step

        // Screen shake decay
        if (screenShakeAmount > 0f) {
            screenShakeAmount -= effectiveDt * 1.5f
            if (screenShakeAmount < 0f) screenShakeAmount = 0f
        }

        // 2. Interpolate player X towards target lane
        val targetX = targetLane * laneWidth
        val lerpSpeed = 12.0f
        playerX += (targetX - playerX) * lerpSpeed * effectiveDt

        // 3. Jump Physics
        if (isJumping) {
            playerY += jumpVelocity * effectiveDt
            jumpVelocity += gravity * effectiveDt
            if (playerY <= 0f) {
                playerY = 0f
                jumpVelocity = 0f
                landingParticlesTrigger++
            }
        }

        // 4. Slide Timer
        if (isSliding) {
            slideTimer -= effectiveDt
            if (slideTimer < 0f) slideTimer = 0f
        }

        // 5. Guardian Monster Logic
        if (monsterCatchUpTimer > 0f) {
            monsterCatchUpTimer -= effectiveDt
            // Monster is catching up or caught up!
            monsterDistance -= effectiveDt * 0.6f
        } else {
            // Monster slowly recedes back to safe distance if running smoothly
            if (monsterDistance < safeMonsterDistance) {
                monsterDistance += effectiveDt * 0.3f
            }
        }

        if (monsterDistance <= 1.0f) {
            deathCause = "CAUGHT BY THE GUARDIAN BEAST!"
            triggerGameOver()
            return
        }

        // 6. Update Power-up timers
        val powerUpKeys = activePowerUps.keys.toList()
        for (pu in powerUpKeys) {
            val timer = activePowerUps[pu] ?: 0f
            if (timer > 0) {
                activePowerUps[pu] = timer - effectiveDt
            }
        }

        // 7. Update procedural segments, clean old ones & spawn new ones
        // Player runs down -Z axis, so the player runs into negative Z space.
        val playerZ = -distanceRan
        
        // Remove old segments that are completely behind camera (playerZ + 25.0f)
        val iterator = activeSegments.iterator()
        while (iterator.hasNext()) {
            val seg = iterator.next()
            if (seg.zEnd > playerZ + 25.0f) {
                iterator.remove()
            }
        }

        // Keep 8 segments buffered ahead
        while (activeSegments.size < 8) {
            spawnSegment(empty = false)
        }

        // 8. Update coins, obstacles, and check collisions
        checkCollisionsAndPowerups(playerZ, effectiveDt)
    }

    private fun spawnSegment(empty: Boolean) {
        val startZ = lastSegmentZ
        val endZ = startZ - segmentLength
        val segment = Segment(startZ, endZ, segmentCounter++)

        if (!empty) {
            val dist = distanceRan
            // Base probabilities that scale with distance (Difficulty Scaling)
            val obstacleProb = (0.35f + (dist * 0.0001f)).coerceAtMost(0.65f) // climbs from 35% to 65%
            val powerUpProb = 0.10f // flat 10%

            val r = Random.nextFloat()
            if (r < powerUpProb) {
                spawnPowerUpForSegment(segment)
            } else if (r < powerUpProb + obstacleProb) {
                spawnObstacleForSegment(segment)
            } else {
                spawnCoinsForSegment(segment)
            }
        }

        activeSegments.add(segment)
        lastSegmentZ = endZ
    }

    private fun spawnCoinsForSegment(seg: Segment) {
        val formation = Random.nextInt(3) // 0: Line, 1: Circle, 2: ZigZag
        val lane = Random.nextInt(3) - 1 // Left, Center, Right
        val laneX = lane * laneWidth
        val baseZ = seg.zStart - 5f

        when (formation) {
            0 -> { // Trail of 5 coins
                for (i in 0 until 5) {
                    seg.coins.add(Coin(laneX, 0.4f, baseZ - i * 3f, 1))
                }
            }
            1 -> { // Circle formation (on center lane)
                val centerX = 0f
                val centerZ = baseZ - 6f
                val radius = 1.0f
                for (i in 0 until 8) {
                    val angle = (i * Math.PI / 4).toFloat()
                    val cx = centerX + radius * sin(angle)
                    val cy = 0.5f + radius * (sin(angle) + 1f) * 0.5f
                    seg.coins.add(Coin(cx, cy, centerZ, 1))
                }
            }
            2 -> { // Zig zag trail across lanes
                for (i in 0 until 5) {
                    val sideLane = ((i % 3) - 1) * laneWidth
                    seg.coins.add(Coin(sideLane, 0.4f, baseZ - i * 4f, 1))
                }
            }
        }
    }

    private fun spawnObstacleForSegment(seg: Segment) {
        val obstacleTypes = ObstacleType.values()
        val type = obstacleTypes[Random.nextInt(obstacleTypes.size)]
        val lane = Random.nextInt(3) - 1
        val laneX = lane * laneWidth
        val obstacleZ = seg.zStart - 12.0f

        when (type) {
            ObstacleType.BOULDER -> {
                // Rolling boulder rolls down a random lane
                seg.obstacles.add(Obstacle(type, laneX, 0.8f, obstacleZ, lane, width = 1.3f, height = 1.3f))
            }
            ObstacleType.FIRE_TRAP -> {
                // Fire trap fills one lane, burns periodically
                seg.obstacles.add(Obstacle(type, laneX, 0.1f, obstacleZ, lane, width = 1.5f, height = 0.5f))
            }
            ObstacleType.SPIKES -> {
                // Spike trap pops up
                seg.obstacles.add(Obstacle(type, laneX, 0.1f, obstacleZ, lane, width = 1.4f, height = 1.0f))
            }
            ObstacleType.SWINGING_AXE -> {
                // High axe swings back and forth
                seg.obstacles.add(Obstacle(type, 0f, 2.0f, obstacleZ, 0, width = 3.0f, height = 2.0f))
            }
            ObstacleType.TEMPLE_DOOR -> {
                // Standard block structure blocking all lanes but leaving a bottom or top pass
                val doorPass = Random.nextInt(2) // 0: Pass below, 1: Pass left/right
                if (doorPass == 0) {
                    // Low ceiling: must slide
                    seg.obstacles.add(Obstacle(ObstacleType.LOW_CEILING, 0f, 1.8f, obstacleZ, 0, width = 4.8f, height = 1.5f))
                } else {
                    // Closed left/right doors: pass only in middle
                    seg.obstacles.add(Obstacle(ObstacleType.TEMPLE_DOOR, -laneWidth, 1.0f, obstacleZ, -1, width = 1.2f, height = 2.0f))
                    seg.obstacles.add(Obstacle(ObstacleType.TEMPLE_DOOR, laneWidth, 1.0f, obstacleZ, 1, width = 1.2f, height = 2.0f))
                }
            }
            ObstacleType.COLLAPSED_BRIDGE -> {
                // Jump gap obstacle
                seg.obstacles.add(Obstacle(type, laneX, 0.0f, obstacleZ, lane, width = 1.5f, height = 0.1f))
            }
            ObstacleType.LOG -> {
                // Log blocks lane, must jump
                seg.obstacles.add(Obstacle(type, laneX, 0.4f, obstacleZ, lane, width = 1.5f, height = 0.6f))
            }
            ObstacleType.ROTATING_HAMMER -> {
                // Spinner obstacle
                seg.obstacles.add(Obstacle(type, laneX, 0.8f, obstacleZ, lane, width = 1.2f, height = 1.5f))
            }
            ObstacleType.LOW_CEILING -> {
                // Must slide
                seg.obstacles.add(Obstacle(type, 0f, 1.8f, obstacleZ, 0, width = 4.8f, height = 1.3f))
            }
        }
    }

    private fun spawnPowerUpForSegment(seg: Segment) {
        val powerups = PowerUp.values()
        val chosenPower = powerups[Random.nextInt(powerups.size)]
        val lane = Random.nextInt(3) - 1
        val laneX = lane * laneWidth
        val targetZ = seg.zStart - 12f

        // Represent powerups as special coins with higher/negative value tags to signify type
        val idValue = - (chosenPower.ordinal + 10) // Negative values code for specific PowerUps!
        seg.coins.add(Coin(laneX, 0.6f, targetZ, idValue))
    }

    private fun checkCollisionsAndPowerups(playerZ: Float, dt: Float) {
        val playerColRadius = 0.5f

        for (seg in activeSegments) {
            // Update obstacle dynamic animations
            for (obs in seg.obstacles) {
                obs.animPhase += dt * 3f
                if (obs.passed) continue

                // Check distance along Z axis with Continuous Collision Detection (CCD)
                val step = runSpeed * dt
                val prevPlayerZ = playerZ + step
                val crossedZ = (prevPlayerZ >= obs.z && playerZ <= obs.z) || (abs(obs.z - playerZ) < 1.1f)
                if (crossedZ) {
                    
                    // Specific lane check
                    var laneOverlap = false
                    if (obs.lane == 0 && obs.width > 3f) {
                        // Wide obstacle, hits all lanes
                        laneOverlap = true
                    } else if (obs.lane == targetLane) {
                        laneOverlap = true
                    }

                    if (laneOverlap) {
                        // Check height/slide constraints
                        var isCollision = false
                        var isInstantDeath = false
                        when (obs.type) {
                            ObstacleType.LOW_CEILING -> {
                                if (!isSliding) isCollision = true
                            }
                            ObstacleType.LOG -> {
                                if (!isJumping) isCollision = true
                            }
                            ObstacleType.COLLAPSED_BRIDGE -> {
                                if (!isJumping) {
                                    isCollision = true
                                    isInstantDeath = true
                                }
                            }
                            ObstacleType.FIRE_TRAP -> {
                                // Dynamic burning based on sin
                                val isBurning = sin(obs.animPhase) > 0f
                                if (isBurning && !isJumping) isCollision = true
                            }
                            else -> {
                                // Default bounding box overlapping (sliding still collides with standard obstacles)
                                if (playerY < obs.height) {
                                    isCollision = true
                                }
                            }
                        }

                        if (isCollision) {
                            obs.passed = true
                            if (isInstantDeath) {
                                deathCause = "FELL INTO THE ANCIENT RIVER GAP!"
                                triggerGameOver()
                            } else {
                                handleObstacleHit()
                            }
                        }
                    }
                }

                // Mark passed
                if (obs.z > playerZ + 2f) {
                    obs.passed = true
                }
            }

            // Coin and Power-up collections
            for (coin in seg.coins) {
                if (coin.collected) continue

                // Coin magnet attraction pull logic
                if (isPowerUpActive(PowerUp.MAGNET) && coin.value > 0) {
                    val distToPlayer = calculateDist(coin.x, coin.y, coin.z, playerX, playerY, playerZ)
                    if (distToPlayer < 7.0f) {
                        coin.magnetAttracted = true
                        // Pull towards player
                        val pullSpeed = 15.0f * dt
                        coin.x += (playerX - coin.x) * pullSpeed
                        coin.y += (playerY - coin.y) * pullSpeed
                        coin.z += (playerZ - coin.z) * pullSpeed
                    }
                }

                // Direct collision check
                val dist = calculateDist(coin.x, coin.y, coin.z, playerX, playerY, playerZ)
                if (dist < 0.8f) {
                    coin.collected = true
                    
                    if (coin.value < 0) {
                        // Power Up collected!
                        val powerOrdinal = -coin.value - 10
                        val powerUp = PowerUp.values()[powerOrdinal]
                        activatePowerUp(powerUp)
                        soundManager.playCoinSound()
                        synchronized(coinSparksToSpawn) {
                            coinSparksToSpawn.add(floatArrayOf(coin.x, coin.y, coin.z, 0.2f, 0.8f, 1.0f)) // Blue sparks
                        }
                    } else {
                        // Normal coin collected!
                        val valueMultiplier = if (isPowerUpActive(PowerUp.DOUBLE_COINS)) 2 else 1
                        val coinsGained = coin.value * valueMultiplier
                        currentRunCoins += coinsGained
                        coinsCollected += coinsGained
                        soundManager.playCoinSound()
                        synchronized(coinSparksToSpawn) {
                            coinSparksToSpawn.add(floatArrayOf(coin.x, coin.y, coin.z, 1.0f, 0.85f, 0.1f)) // Gold sparks
                        }
                    }
                }
            }
        }
    }

    private fun handleObstacleHit() {
        if (isPowerUpActive(PowerUp.INVINCIBLE)) {
            // Invincible: pass straight through!
            return
        }

        if (isPowerUpActive(PowerUp.SHIELD)) {
            // Shield absorbs collision hit
            activePowerUps[PowerUp.SHIELD] = 0f
            screenShakeAmount = 0.5f
            soundManager.playObstacleHitSound()
            return
        }

        // Deduct life
        playerHealth--
        soundManager.playObstacleHitSound()
        screenShakeAmount = 1.2f

        // Stumble: Bring the monster closer
        monsterDistance = (monsterDistance - 1.5f).coerceAtLeast(0.5f)

        if (playerHealth <= 0) {
            deathCause = "COLLIDED WITH TOO MANY OBSTACLES!"
            triggerGameOver()
        } else if (monsterDistance <= 1.0f) {
            deathCause = "CAUGHT BY THE GUARDIAN BEAST!"
            triggerGameOver()
        } else {
            // Stumbled! Guardian catches up!
            monsterCatchUpTimer = 3.5f // 3.5 seconds catchup threat window
            soundManager.playMonsterRoarSound()
        }
    }

    private fun activatePowerUp(powerUp: PowerUp) {
        val duration = when (powerUp) {
            PowerUp.MAGNET -> 12f
            PowerUp.SHIELD -> 15f
            PowerUp.INVINCIBLE -> 8f
            PowerUp.SPEED -> 6f
            PowerUp.JUMP -> 12f
            PowerUp.SLOW_MO -> 10f
            PowerUp.DOUBLE_COINS -> 15f
        }
        activePowerUps[powerUp] = duration
    }

    private fun triggerGameOver() {
        gameState = State.GAME_OVER
        soundManager.playObstacleHitSound()
        soundManager.playMonsterRoarSound()

        // Save progress to Jetpack DataStore asynchronously
        val totalScore = (distanceRan.toInt() + currentRunCoins * 10)
        engineScope.launch {
            if (totalScore > highScore) {
                highScore = totalScore
                dataStore.saveHighScore(totalScore)
            }
            dataStore.addCoins(currentRunCoins)
        }
    }

    private fun calculateDist(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        val dz = z1 - z2
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun equipGoldenSkin(equipped: Boolean) {
        isGoldenSkinEquipped = equipped
    }

    fun purchaseGoldenSkin(onSuccess: () -> Unit, onFailure: () -> Unit) {
        engineScope.launch {
            val success = dataStore.spendCoins(300) // Price is 300 coins
            if (success) {
                dataStore.unlockGoldenSkin()
                isGoldenSkinEquipped = true
                onSuccess()
            } else {
                onFailure()
            }
        }
    }

    fun onDestroy() {
        engineScope.cancel()
    }
}
