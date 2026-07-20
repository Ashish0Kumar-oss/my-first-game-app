package com.example.opengl

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.example.game.GameEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class GameRenderer(
    private val context: Context,
    private val gameEngine: GameEngine
) : GLSurfaceView.Renderer {

    // Smooth Camera lerp properties
    private var curCameraX = 0f
    private var curCameraY = 2.4f
    private var curCameraZ = 4.2f
    private var lastLandingTrigger = 0

    // Matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val mvMatrix = FloatArray(16)

    // Shader program
    private var programId = 0
    private var mvpMatrixHandle = 0
    private var mvMatrixHandle = 0
    private var positionHandle = 0
    private var normalHandle = 0
    private var colorHandle = 0
    private var lightDirHandle = 0
    private var fogColorHandle = 0

    // High performance buffers (initialized once, no allocations in draw loop)
    private lateinit var cubeBuffer: FloatBuffer
    private lateinit var sphereBuffer: FloatBuffer
    private lateinit var coneBuffer: FloatBuffer
    private lateinit var cylinderBuffer: FloatBuffer

    private var cubeVertexCount = 0
    private var sphereVertexCount = 0
    private var coneVertexCount = 0
    private var cylinderVertexCount = 0

    // Dynamic light direction & colors (Day/Night cycle)
    private val lightDir = floatArrayOf(0.5f, 1.0f, 0.3f)
    private val fogColor = floatArrayOf(0.08f, 0.15f, 0.12f) // Ancient emerald jungle fog
    private val nightFogColor = floatArrayOf(0.03f, 0.04f, 0.08f) // Mystical night violet fog

    // Time cycle
    private var gameTime = 0f

    // Particle pool
    class Particle(
        var x: Float, var y: Float, var z: Float,
        var vx: Float, var vy: Float, var vz: Float,
        var life: Float, var maxLife: Float,
        var r: Float, var g: Float, var b: Float
    )
    private val particlePool = Array(150) {
        Particle(0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Set background clear color
        GLES30.glClearColor(fogColor[0], fogColor[1], fogColor[2], 1.0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        // Compile Shader code
        initShaders()

        // Generate geometry meshes
        buildMeshes()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 60.0f, ratio, 0.5f, 75.0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Frame time update
        val currentTime = SystemClock.uptimeMillis() / 1000f
        val dt = if (gameTime == 0f) 0.016f else (currentTime - gameTime)
        gameTime = currentTime

        // Update physics state inside renderer loop safely
        gameEngine.update(dt)

        // Clear buffers
        val cycle = (gameTime * 0.02f) % 2.0f // Day/Night Cycle speed
        val isNight = cycle > 1.0f
        val transition = if (isNight) 2.0f - cycle else cycle
        
        // Linear interpolation of background/fog color
        val curFogR = lerp(fogColor[0], nightFogColor[0], transition)
        val curFogG = lerp(fogColor[1], nightFogColor[1], transition)
        val curFogB = lerp(fogColor[2], nightFogColor[2], transition)

        GLES30.glClearColor(curFogR, curFogG, curFogB, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // Set Light direction based on day/night progress
        lightDir[0] = sin(gameTime * 0.1f)
        lightDir[1] = cos(gameTime * 0.1f).coerceAtLeast(0.1f)
        lightDir[2] = 0.5f

        // 1. Follow Camera tracking Setup (Smooth Lerp + Dynamic Shaking)
        val pX = gameEngine.playerX
        val pY = gameEngine.playerY
        val pZ = -gameEngine.distanceRan

        // Dynamic camera shaking on collisions
        val shakeX = (Math.random().toFloat() - 0.5f) * gameEngine.screenShakeAmount
        val shakeY = (Math.random().toFloat() - 0.5f) * gameEngine.screenShakeAmount

        // Lag camera behind player smoothly (with Lerp)
        val targetCamX = pX * 0.8f
        val targetCamY = pY + 2.4f
        val targetCamZ = pZ + 4.2f

        if (gameEngine.distanceRan < 0.2f) {
            // Instant snap on reset/start
            curCameraX = targetCamX
            curCameraY = targetCamY
            curCameraZ = targetCamZ
        } else {
            // Smooth interpolation
            val camLerpFactor = (dt * 5.0f).coerceIn(0f, 1f)
            curCameraX = lerp(curCameraX, targetCamX, camLerpFactor)
            curCameraY = lerp(curCameraY, targetCamY, camLerpFactor)
            curCameraZ = lerp(curCameraZ, targetCamZ, camLerpFactor)
        }

        val finalCamX = curCameraX + shakeX
        val finalCamY = curCameraY + shakeY
        val finalCamZ = curCameraZ

        Matrix.setLookAtM(
            viewMatrix, 0,
            finalCamX, finalCamY, finalCamZ, // Camera location
            pX, pY + 0.6f, pZ - 2.5f, // View focus point ahead of runner
            0f, 1f, 0f // Up vector
        )

        // Activate Shader
        GLES30.glUseProgram(programId)

        // Pass uniform light configurations
        GLES30.glUniform3fv(lightDirHandle, 1, lightDir, 0)
        GLES30.glUniform3f(fogColorHandle, curFogR, curFogG, curFogB)

        // 2. Render Scene Elements
        renderPath(pZ)
        renderEnvironment(pZ)
        renderObstacles(pZ)
        renderCoinsAndPowerups(pZ)
        renderPlayer(pX, pY, pZ)
        renderMonster(pZ)
        
        // 3. Render GPU simulated visual particles
        updateAndRenderParticles(pX, pY, pZ, dt)
    }

    // --- Shader Initialization ---

    private fun initShaders() {
        val vertexShaderCode = """#version 300 es
            uniform mat4 uMVPMatrix;
            uniform mat4 uMVMatrix;
            uniform vec3 uLightDir;
            uniform vec4 uColor;
            
            in vec4 aPosition;
            in vec3 aNormal;
            
            out vec4 vColor;
            out float vFogFactor;
            
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                
                // Normal transform & lighting calculation
                vec3 normal = normalize(vec3(uMVMatrix * vec4(aNormal, 0.0)));
                float diffuse = max(dot(normal, normalize(uLightDir)), 0.25);
                
                // Ambient + diffuse mix
                vColor = vec4(uColor.rgb * (diffuse + 0.15), uColor.a);
                
                // Camera-space fog calculations (Exponential-squared)
                vec4 camPos = uMVMatrix * aPosition;
                float dist = length(camPos.xyz);
                float density = 0.035;
                vFogFactor = exp(-pow(dist * density, 2.0));
                vFogFactor = clamp(vFogFactor, 0.0, 1.0);
            }
        """.trimIndent()

        val fragmentShaderCode = """#version 300 es
            precision mediump float;
            
            uniform vec3 uFogColor;
            
            in vec4 vColor;
            in float vFogFactor;
            
            out vec4 fragColor;
            
            void main() {
                // Blend fog color smoothly with vertex color
                fragColor = mix(vec4(uFogColor, 1.0), vColor, vFogFactor);
            }
        """.trimIndent()

        val vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vertexShader)
        GLES30.glAttachShader(programId, fragmentShader)
        GLES30.glLinkProgram(programId)

        // Get Handles
        mvpMatrixHandle = GLES30.glGetUniformLocation(programId, "uMVPMatrix")
        mvMatrixHandle = GLES30.glGetUniformLocation(programId, "uMVMatrix")
        lightDirHandle = GLES30.glGetUniformLocation(programId, "uLightDir")
        colorHandle = GLES30.glGetUniformLocation(programId, "uColor")
        fogColorHandle = GLES30.glGetUniformLocation(programId, "uFogColor")
        
        positionHandle = GLES30.glGetAttribLocation(programId, "aPosition")
        normalHandle = GLES30.glGetAttribLocation(programId, "aNormal")
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, shaderCode)
        GLES30.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val error = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw RuntimeException("Shader compilation failed: $error")
        }
        return shader
    }

    // --- Mesh Generation (VBO / Arrays preallocated) ---

    private fun buildMeshes() {
        // Build Cube mesh
        val cubeData = GeometryBuilder.generateCube()
        cubeVertexCount = cubeData.size / 6
        cubeBuffer = ByteBuffer.allocateDirect(cubeData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(cubeData)
        cubeBuffer.position(0)

        // Build Sphere mesh (Boulder)
        val sphereData = GeometryBuilder.generateSphere(16, 16)
        sphereVertexCount = sphereData.size / 6
        sphereBuffer = ByteBuffer.allocateDirect(sphereData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(sphereData)
        sphereBuffer.position(0)

        // Build Cone mesh (Spikes, Trees)
        val coneData = GeometryBuilder.generateCone(16)
        coneVertexCount = coneData.size / 6
        coneBuffer = ByteBuffer.allocateDirect(coneData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(coneData)
        coneBuffer.position(0)

        // Build Cylinder mesh (Coins, Logs)
        val cylinderData = GeometryBuilder.generateCylinder(16)
        cylinderVertexCount = cylinderData.size / 6
        cylinderBuffer = ByteBuffer.allocateDirect(cylinderData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(cylinderData)
        cylinderBuffer.position(0)
    }

    // --- Render Modules ---

    // Draw the continuous procedural path tiles
    private fun renderPath(playerZ: Float) {
        // Draw stone path strips
        // Loop through active generated game segments
        for (seg in gameEngine.activeSegments) {
            val centerZ = (seg.zStart + seg.zEnd) / 2.0f
            val length = seg.zStart - seg.zEnd

            // Left Border Wall (ancient temple look)
            drawModel(cubeBuffer, cubeVertexCount, -2.4f, 0.4f, centerZ, 0.3f, 0.8f, length, floatArrayOf(0.4f, 0.38f, 0.35f, 1.0f))
            // Right Border Wall
            drawModel(cubeBuffer, cubeVertexCount, 2.4f, 0.4f, centerZ, 0.3f, 0.8f, length, floatArrayOf(0.4f, 0.38f, 0.35f, 1.0f))

            // Stone Path ground slabs
            // Alternating slab tones to produce procedural pattern
            val colorFactor = if (seg.segmentId % 2 == 0) 0.35f else 0.31f
            drawModel(cubeBuffer, cubeVertexCount, 0f, -0.05f, centerZ, 4.5f, 0.1f, length, floatArrayOf(colorFactor, colorFactor - 0.02f, colorFactor - 0.05f, 1.0f))
        }
    }

    // Draw temple columns, ruins, background mountain cones and foliage to create immersive feel
    private fun renderEnvironment(playerZ: Float) {
        // Ambient pillars and backgrounds relative to segments
        for (seg in gameEngine.activeSegments) {
            val z = seg.zStart - 10f

            // Columns flanking the stone road periodically
            if (seg.segmentId % 2 == 0) {
                // Left Column
                drawModel(cylinderBuffer, cylinderVertexCount, -3.2f, 1.8f, z, 0.4f, 3.6f, 0.4f, floatArrayOf(0.48f, 0.45f, 0.42f, 1.0f))
                drawModel(cubeBuffer, cubeVertexCount, -3.2f, 3.6f, z, 0.9f, 0.2f, 0.9f, floatArrayOf(0.42f, 0.4f, 0.37f, 1.0f)) // Cap

                // Right Column
                drawModel(cylinderBuffer, cylinderVertexCount, 3.2f, 1.8f, z, 0.4f, 3.6f, 0.4f, floatArrayOf(0.48f, 0.45f, 0.42f, 1.0f))
                drawModel(cubeBuffer, cubeVertexCount, 3.2f, 3.6f, z, 0.9f, 0.2f, 0.9f, floatArrayOf(0.42f, 0.4f, 0.37f, 1.0f)) // Cap
            }

            // Distant Mountains & Jungle Trees along edge of track
            if (seg.segmentId % 3 == 0) {
                // Giant Mountains left
                drawModel(coneBuffer, coneVertexCount, -15.0f, 0f, z, 12f, 18f, 12f, floatArrayOf(0.08f, 0.12f, 0.15f, 1.0f))
                // Trees left
                drawModel(cylinderBuffer, cylinderVertexCount, -6.0f, 1.2f, z + 4f, 0.3f, 2.4f, 0.3f, floatArrayOf(0.28f, 0.18f, 0.12f, 1.0f)) // Trunk
                drawModel(coneBuffer, coneVertexCount, -6.0f, 3.6f, z + 4f, 2.5f, 4.0f, 2.5f, floatArrayOf(0.05f, 0.22f, 0.11f, 1.0f)) // Leaves

                // Giant Mountains right
                drawModel(coneBuffer, coneVertexCount, 15.0f, 0f, z - 5f, 10f, 15f, 10f, floatArrayOf(0.08f, 0.12f, 0.15f, 1.0f))
                // Trees right
                drawModel(cylinderBuffer, cylinderVertexCount, 6.0f, 1.2f, z - 3f, 0.25f, 2.4f, 0.25f, floatArrayOf(0.28f, 0.18f, 0.12f, 1.0f)) // Trunk
                drawModel(coneBuffer, coneVertexCount, 6.0f, 3.6f, z - 3f, 2.2f, 3.6f, 2.2f, floatArrayOf(0.04f, 0.18f, 0.09f, 1.0f)) // Leaves
            }

            // High overhead moving clouds
            val cloudZ = z + sin(gameTime * 0.05f) * 5f
            drawModel(cubeBuffer, cubeVertexCount, -8.0f + (seg.segmentId % 5) * 4f, 12.0f, cloudZ, 5.0f, 1.2f, 3.0f, floatArrayOf(0.85f, 0.88f, 0.92f, 0.35f))
        }
    }

    // Render generated obstacles based on active segments
    private fun renderObstacles(playerZ: Float) {
        for (seg in gameEngine.activeSegments) {
            for (obs in seg.obstacles) {
                if (obs.z > playerZ + 5.0f) continue // Skip drawing far-behind obstacles

                when (obs.type) {
                    GameEngine.ObstacleType.BOULDER -> {
                        // Sphere rotating as it rolls
                        val rotation = (obs.z * 50f) % 360f
                        drawModel(
                            sphereBuffer, sphereVertexCount,
                            obs.x, obs.y, obs.z,
                            obs.width, obs.height, obs.width,
                            floatArrayOf(0.52f, 0.45f, 0.42f, 1.0f),
                            rotY = 0f, rotX = rotation
                        )
                    }
                    GameEngine.ObstacleType.FIRE_TRAP -> {
                        // Red glowing fire floor plate
                        val colorIntensity = (sin(obs.animPhase) + 1f) * 0.5f // Pulsate fire glow
                        val redCol = floatArrayOf(1.0f, 0.25f * colorIntensity, 0.0f, 1.0f)
                        drawModel(cubeBuffer, cubeVertexCount, obs.x, obs.y, obs.z, obs.width, obs.height, 1.5f, redCol)
                    }
                    GameEngine.ObstacleType.SPIKES -> {
                        // Rising metallic cones
                        val riseY = if (sin(obs.animPhase) > 0f) 0.5f else -0.3f
                        drawModel(coneBuffer, coneVertexCount, obs.x, obs.y + riseY, obs.z, 0.3f, 0.9f, 0.3f, floatArrayOf(0.55f, 0.58f, 0.62f, 1.0f))
                        drawModel(coneBuffer, coneVertexCount, obs.x - 0.4f, obs.y + riseY, obs.z, 0.3f, 0.9f, 0.3f, floatArrayOf(0.55f, 0.58f, 0.62f, 1.0f))
                        drawModel(coneBuffer, coneVertexCount, obs.x + 0.4f, obs.y + riseY, obs.z, 0.3f, 0.9f, 0.3f, floatArrayOf(0.55f, 0.58f, 0.62f, 1.0f))
                    }
                    GameEngine.ObstacleType.SWINGING_AXE -> {
                        // Swing animation on pendulum
                        val swingAngle = sin(obs.animPhase * 1.5f) * 45f
                        // Pendulum chain
                        drawModel(
                            cubeBuffer, cubeVertexCount,
                            obs.x, obs.y + 1.2f, obs.z,
                            0.1f, 2.2f, 0.1f,
                            floatArrayOf(0.6f, 0.6f, 0.6f, 1.0f),
                            rotZ = swingAngle
                        )
                        // Blade
                        drawModel(
                            cubeBuffer, cubeVertexCount,
                            obs.x + sin(Math.toRadians(swingAngle.toDouble())).toFloat() * 2.2f,
                            obs.y + 1.2f - cos(Math.toRadians(swingAngle.toDouble())).toFloat() * 2.2f,
                            obs.z,
                            1.1f, 0.4f, 0.1f,
                            floatArrayOf(0.72f, 0.75f, 0.78f, 1.0f),
                            rotZ = swingAngle
                        )
                    }
                    GameEngine.ObstacleType.TEMPLE_DOOR -> {
                        // Vertical column door barrier
                        drawModel(cubeBuffer, cubeVertexCount, obs.x, obs.y, obs.z, obs.width, obs.height, 0.5f, floatArrayOf(0.35f, 0.28f, 0.22f, 1.0f))
                    }
                    GameEngine.ObstacleType.LOG -> {
                        // Horizontal trunk
                        drawModel(
                            cylinderBuffer, cylinderVertexCount,
                            obs.x, obs.y, obs.z,
                            obs.width, 0.4f, 0.4f,
                            floatArrayOf(0.36f, 0.24f, 0.15f, 1.0f),
                            rotZ = 90f
                        )
                    }
                    GameEngine.ObstacleType.LOW_CEILING -> {
                        // Heavy stone arch spanning across road
                        drawModel(cubeBuffer, cubeVertexCount, obs.x, obs.y, obs.z, obs.width, obs.height, 0.8f, floatArrayOf(0.42f, 0.38f, 0.34f, 1.0f))
                    }
                    else -> {}
                }
            }
        }
    }

    // Render Gold Coins and special glowing Power Up markers
    private fun renderCoinsAndPowerups(playerZ: Float) {
        val spinRotation = (gameTime * 140f) % 360f

        for (seg in gameEngine.activeSegments) {
            for (coin in seg.coins) {
                if (coin.collected || coin.z > playerZ + 5.0f) continue

                if (coin.value < 0) {
                    // Power Up! Drawn as a glowing multi-faced crystal spinning fast
                    val powerUpType = GameEngine.PowerUp.values()[-coin.value - 10]
                    val color = when (powerUpType) {
                        GameEngine.PowerUp.MAGNET -> floatArrayOf(0.2f, 0.6f, 1.0f, 1.0f) // Blue
                        GameEngine.PowerUp.SHIELD -> floatArrayOf(0.3f, 1.0f, 0.3f, 1.0f) // Green
                        GameEngine.PowerUp.INVINCIBLE -> floatArrayOf(1.0f, 0.85f, 0.1f, 1.0f) // Gold
                        GameEngine.PowerUp.SPEED -> floatArrayOf(1.0f, 0.2f, 0.2f, 1.0f) // Red
                        GameEngine.PowerUp.JUMP -> floatArrayOf(0.9f, 0.1f, 1.0f, 1.0f) // Purple
                        GameEngine.PowerUp.SLOW_MO -> floatArrayOf(0.1f, 0.9f, 0.9f, 1.0f) // Cyan
                        GameEngine.PowerUp.DOUBLE_COINS -> floatArrayOf(1.0f, 0.5f, 0.0f, 1.0f) // Orange
                    }
                    // Inner crystal
                    drawModel(
                        coneBuffer, coneVertexCount,
                        coin.x, coin.y, coin.z,
                        0.45f, 0.85f, 0.45f,
                        color,
                        rotY = spinRotation * 1.5f,
                        rotX = 180f
                    )
                    // Floating halo/particles can spawn around it!
                } else {
                    // Normal Gold coin: drawn as a thin, gold cylinder stood on edge and spinning
                    drawModel(
                        cylinderBuffer, cylinderVertexCount,
                        coin.x, coin.y, coin.z,
                        0.4f, 0.1f, 0.4f,
                        floatArrayOf(1.0f, 0.82f, 0.15f, 1.0f),
                        rotY = spinRotation,
                        rotX = 90f // Sideways standing ring look
                    )
                }
            }
        }
    }

    // Render the running, jumping, sliding 3D avatar of the player
    private fun renderPlayer(pX: Float, pY: Float, pZ: Float) {
        // Equip special skins (e.g., gold body skin unlocked from shop)
        val bodyColor = if (gameEngine.isGoldenSkinEquipped) {
            floatArrayOf(1.0f, 0.84f, 0.0f, 1.0f) // Metallic Shiny Gold
        } else {
            floatArrayOf(0.2f, 0.65f, 0.92f, 1.0f) // Heroic Azure Blue
        }

        // Apply visual aura for active powerups
        var headColor = floatArrayOf(0.85f, 0.85f, 0.85f, 1.0f)
        if (gameEngine.isPowerUpActive(GameEngine.PowerUp.INVINCIBLE)) {
            // Pulse crazy rainbow colors!
            headColor = floatArrayOf(
                (sin(gameTime * 15f) + 1f) * 0.5f,
                (cos(gameTime * 12f) + 1f) * 0.5f,
                (sin(gameTime * 9f) + 1f) * 0.5f,
                1.0f
            )
        } else if (gameEngine.isPowerUpActive(GameEngine.PowerUp.SHIELD)) {
            // Cyan head protective aura
            headColor = floatArrayOf(0.2f, 0.9f, 0.9f, 1.0f)
        }

        // Procedural leg Swing animation for running
        val swingFreq = if (gameEngine.isPowerUpActive(GameEngine.PowerUp.SPEED)) 25f else 16f
        val legSwingAngle = if (gameEngine.isJumping || gameEngine.isSliding) 0f else sin(gameTime * swingFreq) * 35f

        // Player height scaling on crouch/slide
        val charHeightScale = if (gameEngine.isSliding) 0.35f else 1.0f
        val bodyY = pY + 0.5f * charHeightScale

        // Tilt torso and head backward if they have stumbled
        val hitTilt = if (gameEngine.monsterDistance < 4.0f) -20f else 0f

        // Torso body block
        drawModel(cubeBuffer, cubeVertexCount, pX, bodyY, pZ, 0.45f, 0.6f * charHeightScale, 0.3f, bodyColor, rotX = hitTilt)

        // Head
        drawModel(sphereBuffer, sphereVertexCount, pX, bodyY + 0.5f * charHeightScale, pZ, 0.35f, 0.35f, 0.35f, headColor, rotX = hitTilt)

        // Legs
        // Left Leg
        drawModel(
            cubeBuffer, cubeVertexCount,
            pX - 0.15f, bodyY - 0.4f * charHeightScale, pZ,
            0.14f, 0.45f * charHeightScale, 0.14f,
            floatArrayOf(0.15f, 0.15f, 0.15f, 1.0f),
            rotX = legSwingAngle
        )
        // Right Leg
        drawModel(
            cubeBuffer, cubeVertexCount,
            pX + 0.15f, bodyY - 0.4f * charHeightScale, pZ,
            0.14f, 0.45f * charHeightScale, 0.14f,
            floatArrayOf(0.15f, 0.15f, 0.15f, 1.0f),
            rotX = -legSwingAngle
        )

        // Arms (Waving while running)
        drawModel(
            cubeBuffer, cubeVertexCount,
            pX - 0.3f, bodyY + 0.1f * charHeightScale, pZ,
            0.12f, 0.45f * charHeightScale, 0.12f,
            floatArrayOf(0.2f, 0.2f, 0.2f, 1.0f),
            rotX = -legSwingAngle * 0.8f
        )
        drawModel(
            cubeBuffer, cubeVertexCount,
            pX + 0.3f, bodyY + 0.1f * charHeightScale, pZ,
            0.12f, 0.45f * charHeightScale, 0.12f,
            floatArrayOf(0.2f, 0.2f, 0.2f, 1.0f),
            rotX = legSwingAngle * 0.8f
        )

        // Draw protective energy bubble if Shield is equipped
        if (gameEngine.isPowerUpActive(GameEngine.PowerUp.SHIELD)) {
            // Draw transparent-ish bubble overlay
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            drawModel(
                sphereBuffer, sphereVertexCount,
                pX, bodyY, pZ,
                1.4f, 1.4f, 1.4f,
                floatArrayOf(0.2f, 0.8f, 1.0f, 0.3f)
            )
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    // Render the scary pursuing temple monster guardian right behind player
    private fun renderMonster(playerZ: Float) {
        val monsterZ = playerZ + gameEngine.monsterDistance
        
        // Scary dark crimson beast
        val bodyColor = floatArrayOf(0.12f, 0.05f, 0.06f, 1.0f)
        val eyeColor = floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f) // Menacing red glowing eyes

        // Large bulky upper torso
        drawModel(cubeBuffer, cubeVertexCount, gameEngine.playerX * 0.6f, 1.4f, monsterZ, 1.5f, 1.3f, 1.1f, bodyColor)
        
        // Left arm claw
        val armSwing = sin(gameTime * 22f) * 30f
        drawModel(
            cubeBuffer, cubeVertexCount,
            gameEngine.playerX * 0.6f - 1.0f, 1.3f, monsterZ - 0.4f,
            0.35f, 1.1f, 0.35f,
            floatArrayOf(0.25f, 0.05f, 0.05f, 1.0f),
            rotX = -armSwing, rotZ = -15f
        )
        // Right arm claw
        drawModel(
            cubeBuffer, cubeVertexCount,
            gameEngine.playerX * 0.6f + 1.0f, 1.3f, monsterZ - 0.4f,
            0.35f, 1.1f, 0.35f,
            floatArrayOf(0.25f, 0.05f, 0.05f, 1.0f),
            rotX = armSwing, rotZ = 15f
        )

        // Demon horns & eyes
        drawModel(cubeBuffer, cubeVertexCount, gameEngine.playerX * 0.6f - 0.3f, 2.2f, monsterZ - 0.5f, 0.15f, 0.4f, 0.15f, floatArrayOf(0.35f, 0.05f, 0.05f, 1.0f), rotZ = -25f) // Left Horn
        drawModel(cubeBuffer, cubeVertexCount, gameEngine.playerX * 0.6f + 0.3f, 2.2f, monsterZ - 0.5f, 0.15f, 0.4f, 0.15f, floatArrayOf(0.35f, 0.05f, 0.05f, 1.0f), rotZ = 25f) // Right Horn

        drawModel(sphereBuffer, sphereVertexCount, gameEngine.playerX * 0.6f - 0.25f, 1.6f, monsterZ - 0.56f, 0.15f, 0.15f, 0.15f, eyeColor) // Left Eye
        drawModel(sphereBuffer, sphereVertexCount, gameEngine.playerX * 0.6f + 0.25f, 1.6f, monsterZ - 0.56f, 0.15f, 0.15f, 0.15f, eyeColor) // Right Eye
    }

    // --- GPU Particle Simulation ---

    private fun updateAndRenderParticles(pX: Float, pY: Float, pZ: Float, dt: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE) // Additive blend for glows

        // 1. Landing particles trigger detection
        if (gameEngine.landingParticlesTrigger != lastLandingTrigger) {
            lastLandingTrigger = gameEngine.landingParticlesTrigger
            // Spawn a ring of landing dust particles
            for (i in 0 until 15) {
                val p = findDeadParticle()
                if (p != null) {
                    val angle = (i * Math.PI * 2 / 15).toFloat()
                    p.x = pX + cos(angle) * 0.4f
                    p.y = pY + 0.05f
                    p.z = pZ + sin(angle) * 0.4f
                    p.vx = cos(angle) * 3.0f
                    p.vy = Math.random().toFloat() * 1.5f + 0.5f
                    p.vz = sin(angle) * 3.0f
                    p.life = 0.6f
                    p.maxLife = 0.6f
                    p.r = 0.72f; p.g = 0.68f; p.b = 0.62f // Earthy dust
                }
            }
        }

        // 2. Spawn gold sparks / blue powerup sparks on collections
        synchronized(gameEngine.coinSparksToSpawn) {
            for (spark in gameEngine.coinSparksToSpawn) {
                val sx = spark[0]
                val sy = spark[1]
                val sz = spark[2]
                val sr = spark[3]
                val sg = spark[4]
                val sb = spark[5]
                // Spawn 8 radial sparkly particles
                for (i in 0 until 8) {
                    val p = findDeadParticle()
                    if (p != null) {
                        p.x = sx
                        p.y = sy
                        p.z = sz
                        p.vx = (Math.random().toFloat() - 0.5f) * 4.0f
                        p.vy = (Math.random().toFloat() - 0.5f) * 4.0f + 1.0f
                        p.vz = (Math.random().toFloat() - 0.5f) * 4.0f
                        p.life = 0.4f
                        p.maxLife = 0.4f
                        p.r = sr; p.g = sg; p.b = sb
                    }
                }
            }
            gameEngine.coinSparksToSpawn.clear()
        }

        // Spawn dust on run footsteps
        if (gameEngine.gameState == GameEngine.State.RUNNING && !gameEngine.isJumping) {
            val spawnRate = if (gameEngine.isPowerUpActive(GameEngine.PowerUp.SPEED)) 3 else 1
            for (k in 0 until spawnRate) {
                val p = findDeadParticle()
                if (p != null) {
                    p.x = pX + (Math.random().toFloat() - 0.5f) * 0.3f
                    p.y = pY + 0.05f
                    p.z = pZ + (Math.random().toFloat() - 0.5f) * 0.2f
                    p.vx = (Math.random().toFloat() - 0.5f) * 1.5f
                    p.vy = Math.random().toFloat() * 1.5f + 0.5f
                    p.vz = Math.random().toFloat() * 2.0f
                    p.life = 0.5f
                    p.maxLife = 0.5f
                    p.r = 0.65f; p.g = 0.58f; p.b = 0.48f // Dusty gray-brown
                }
            }
        }

        // Spawn gold sparks if magnet or invincibility active
        if (gameEngine.isPowerUpActive(GameEngine.PowerUp.INVINCIBLE)) {
            val p = findDeadParticle()
            if (p != null) {
                p.x = pX + (Math.random().toFloat() - 0.5f) * 0.8f
                p.y = pY + 0.8f + (Math.random().toFloat() - 0.5f) * 0.8f
                p.z = pZ + (Math.random().toFloat() - 0.5f) * 0.8f
                p.vx = (Math.random().toFloat() - 0.5f) * 2.5f
                p.vy = (Math.random().toFloat() - 0.5f) * 2.5f
                p.vz = (Math.random().toFloat() - 0.5f) * 2.5f
                p.life = 0.6f
                p.maxLife = 0.6f
                p.r = 1.0f; p.g = 0.85f; p.b = 0.1f // Glow gold
            }
        }

        // Simulate and draw active particles
        for (p in particlePool) {
            if (p.life <= 0f) continue

            p.life -= dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.z += p.vz * dt

            // Decaying opacity
            val alpha = p.life / p.maxLife
            
            // Draw particle as a tiny floating square
            drawModel(
                cubeBuffer, cubeVertexCount,
                p.x, p.y, p.z,
                0.08f, 0.08f, 0.08f,
                floatArrayOf(p.r, p.g, p.b, alpha)
            )
        }

        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun findDeadParticle(): Particle? {
        for (p in particlePool) {
            if (p.life <= 0f) return p
        }
        return null
    }

    // --- Master model drawing pipeline ---

    private fun drawModel(
        buffer: FloatBuffer,
        vertexCount: Int,
        x: Float, y: Float, z: Float,
        sx: Float, sy: Float, sz: Float,
        color: FloatArray,
        rotY: Float = 0f, rotX: Float = 0f, rotZ: Float = 0f
    ) {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, x, y, z)
        if (rotX != 0f) Matrix.rotateM(modelMatrix, 0, rotX, 1f, 0f, 0f)
        if (rotY != 0f) Matrix.rotateM(modelMatrix, 0, rotY, 0f, 1f, 0f)
        if (rotZ != 0f) Matrix.rotateM(modelMatrix, 0, rotZ, 0f, 0f, 1f)
        Matrix.scaleM(modelMatrix, 0, sx, sy, sz)

        // Calculate model-view matrix and model-view-projection matrix
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        // Supply matrices to shaders
        GLES30.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(mvMatrixHandle, 1, false, mvMatrix, 0)

        // Supply uniform color
        GLES30.glUniform4fv(colorHandle, 1, color, 0)

        // Set pointer indices and draw arrays
        GLES30.glEnableVertexAttribArray(positionHandle)
        GLES30.glEnableVertexAttribArray(normalHandle)

        // Vertex buffer structure: 3 floats position, 3 floats normals
        buffer.position(0)
        GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 24, buffer)
        
        buffer.position(3)
        GLES30.glVertexAttribPointer(normalHandle, 3, GLES30.GL_FLOAT, false, 24, buffer)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)

        GLES30.glDisableVertexAttribArray(positionHandle)
        GLES30.glDisableVertexAttribArray(normalHandle)
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }
}

// Helper mathematical class to construct beautiful 3D procedural shapes in RAM
object GeometryBuilder {

    fun generateCube(): FloatArray {
        // 36 vertices. Format: posX, posY, posZ, normX, normY, normZ
        return floatArrayOf(
            // Front face
            -0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,
             0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,
             0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,
            -0.5f, -0.5f,  0.5f,  0.0f,  0.0f,  1.0f,
             0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,
            -0.5f,  0.5f,  0.5f,  0.0f,  0.0f,  1.0f,

            // Back face
            -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
            -0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
             0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
            -0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
             0.5f,  0.5f, -0.5f,  0.0f,  0.0f, -1.0f,
             0.5f, -0.5f, -0.5f,  0.0f,  0.0f, -1.0f,

            // Left face
            -0.5f, -0.5f, -0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f, -0.5f,  0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f,  0.5f,  0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f, -0.5f, -0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f,  0.5f,  0.5f, -1.0f,  0.0f,  0.0f,
            -0.5f,  0.5f, -0.5f, -1.0f,  0.0f,  0.0f,

            // Right face
             0.5f, -0.5f, -0.5f,  1.0f,  0.0f,  0.0f,
             0.5f,  0.5f, -0.5f,  1.0f,  0.0f,  0.0f,
             0.5f,  0.5f,  0.5f,  1.0f,  0.0f,  0.0f,
             0.5f, -0.5f, -0.5f,  1.0f,  0.0f,  0.0f,
             0.5f,  0.5f,  0.5f,  1.0f,  0.0f,  0.0f,
             0.5f, -0.5f,  0.5f,  1.0f,  0.0f,  0.0f,

            // Top face
            -0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,
            -0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,
             0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,
            -0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,
             0.5f,  0.5f,  0.5f,  0.0f,  1.0f,  0.0f,
             0.5f,  0.5f, -0.5f,  0.0f,  1.0f,  0.0f,

            // Bottom face
            -0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,
             0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,
             0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, -1.0f,  0.0f,
             0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f,
            -0.5f, -0.5f,  0.5f,  0.0f, -1.0f,  0.0f
        )
    }

    fun generateSphere(rings: Int, sectors: Int): FloatArray {
        val list = mutableListOf<Float>()
        val R = 1f / rings.toFloat()
        val S = 1f / sectors.toFloat()

        val grid = Array(rings + 1) { r ->
            Array(sectors + 1) { s ->
                val y = sin(-Math.PI / 2 + Math.PI * r * R).toFloat()
                val x = (cos(2.0 * Math.PI * s * S) * sin(Math.PI * r * R)).toFloat()
                val z = (sin(2.0 * Math.PI * s * S) * sin(Math.PI * r * R)).toFloat()
                floatArrayOf(x * 0.5f, y * 0.5f, z * 0.5f, x, y, z) // Pos, Norm
            }
        }

        for (r in 0 until rings) {
            for (s in 0 until sectors) {
                val v1 = grid[r][s]
                val v2 = grid[r + 1][s]
                val v3 = grid[r + 1][s + 1]
                val v4 = grid[r][s + 1]

                // Triangle 1
                list.addAll(v1.toList())
                list.addAll(v2.toList())
                list.addAll(v3.toList())

                // Triangle 2
                list.addAll(v1.toList())
                list.addAll(v3.toList())
                list.addAll(v4.toList())
            }
        }
        return list.toFloatArray()
    }

    fun generateCone(sectors: Int): FloatArray {
        val list = mutableListOf<Float>()
        val step = (2.0 * Math.PI / sectors).toFloat()

        // Cone faces
        for (i in 0 until sectors) {
            val angle = i * step
            val nextAngle = (i + 1) * step

            val x1 = cos(angle) * 0.5f
            val z1 = sin(angle) * 0.5f
            val x2 = cos(nextAngle) * 0.5f
            val z2 = sin(nextAngle) * 0.5f

            // Side Face triangle (Apex at 0.0, 0.5, 0.0)
            val nx = cos(angle + step / 2f)
            val nz = sin(angle + step / 2f)
            val ny = 0.5f

            list.addAll(listOf(0f, 0.5f, 0f, nx, ny, nz))
            list.addAll(listOf(x2, -0.5f, z2, nx, ny, nz))
            list.addAll(listOf(x1, -0.5f, z1, nx, ny, nz))

            // Bottom base Face triangle
            list.addAll(listOf(0f, -0.5f, 0f, 0f, -1f, 0f))
            list.addAll(listOf(x1, -0.5f, z1, 0f, -1f, 0f))
            list.addAll(listOf(x2, -0.5f, z2, 0f, -1f, 0f))
        }
        return list.toFloatArray()
    }

    fun generateCylinder(sectors: Int): FloatArray {
        val list = mutableListOf<Float>()
        val step = (2.0 * Math.PI / sectors).toFloat()

        for (i in 0 until sectors) {
            val angle = i * step
            val nextAngle = (i + 1) * step

            val x1 = cos(angle) * 0.5f
            val z1 = sin(angle) * 0.5f
            val x2 = cos(nextAngle) * 0.5f
            val z2 = sin(nextAngle) * 0.5f

            // Wall Triangles
            val nx1 = cos(angle)
            val nz1 = sin(angle)
            val nx2 = cos(nextAngle)
            val nz2 = sin(nextAngle)

            // Triangle 1
            list.addAll(listOf(x1, 0.5f, z1, nx1, 0f, nz1))
            list.addAll(listOf(x1, -0.5f, z1, nx1, 0f, nz1))
            list.addAll(listOf(x2, -0.5f, z2, nx2, 0f, nz2))

            // Triangle 2
            list.addAll(listOf(x1, 0.5f, z1, nx1, 0f, nz1))
            list.addAll(listOf(x2, -0.5f, z2, nx2, 0f, nz2))
            list.addAll(listOf(x2, 0.5f, z2, nx2, 0f, nz2))

            // Top Cap
            list.addAll(listOf(0f, 0.5f, 0f, 0f, 1f, 0f))
            list.addAll(listOf(x2, 0.5f, z2, 0f, 1f, 0f))
            list.addAll(listOf(x1, 0.5f, z1, 0f, 1f, 0f))

            // Bottom Cap
            list.addAll(listOf(0f, -0.5f, 0f, 0f, -1f, 0f))
            list.addAll(listOf(x1, -0.5f, z1, 0f, -1f, 0f))
            list.addAll(listOf(x2, -0.5f, z2, 0f, -1f, 0f))
        }
        return list.toFloatArray()
    }
}
