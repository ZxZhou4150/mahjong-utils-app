package io.ssttkkl.mahjongutils.app.components.tile

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import io.ssttkkl.mahjongutils.app.base.utils.LoggerFactory
import io.ssttkkl.mahjongutils.app.components.appscaffold.AppState
import io.ssttkkl.mahjongutils.app.components.tileime.TileImeHostState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import javax.swing.JComponent
import javax.swing.JWindow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private data class OverlaySelection(
    val startX: Int,
    val startY: Int,
    val currentX: Int,
    val currentY: Int,
) {
    fun toRectangle(): Rectangle {
        val left = min(startX, currentX)
        val top = min(startY, currentY)
        return Rectangle(left, top, abs(currentX - startX), abs(currentY - startY))
    }
}

private class SelectionOverlayWindow(
    private val screenSize: Dimension,
    private val onSelected: (Rectangle?) -> Unit,
    private val onCancel: () -> Unit,
) : JWindow() {
    private val selectionState: MutableState<OverlaySelection?> = mutableStateOf(null)

    init {
        isAlwaysOnTop = true
        background = Color(0, 0, 0, 1)
        setBounds(0, 0, screenSize.width, screenSize.height)

        val panel = object : JComponent() {
            override fun paintComponent(graphics: Graphics) {
                val g2 = graphics as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                )

                g2.color = Color(0, 0, 0, 80)
                g2.fillRect(0, 0, width, height)

                selectionState.value?.toRectangle()?.takeIf { it.width > 0 && it.height > 0 }?.let { rect ->
                    g2.color = Color(255, 255, 255, 32)
                    g2.fillRect(rect.x, rect.y, rect.width, rect.height)
                    g2.color = Color(255, 80, 80, 220)
                    g2.stroke = java.awt.BasicStroke(2f)
                    g2.drawRect(rect.x, rect.y, rect.width, rect.height)
                }
            }
        }
        contentPane = panel

        val mouseAdapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                selectionState.value = OverlaySelection(e.x, e.y, e.x, e.y)
                panel.repaint()
            }

            override fun mouseDragged(e: MouseEvent) {
                selectionState.value = selectionState.value?.copy(currentX = e.x, currentY = e.y)
                panel.repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                selectionState.value = selectionState.value?.copy(currentX = e.x, currentY = e.y)
                panel.repaint()
                onSelected(selectionState.value?.toRectangle())
            }
        }

        panel.addMouseListener(mouseAdapter)
        panel.addMouseMotionListener(mouseAdapter)
        panel.isFocusable = true

        addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE || e.keyCode == KeyEvent.VK_Q) {
                    onCancel()
                }
            }
        })
    }

    fun open() {
        isVisible = true
        toFront()
        requestFocus()
    }
}

private class SelectionBorderWindow(
    rect: Rectangle,
) : JWindow() {
    private val borderWidth = 3

    init {
        isAlwaysOnTop = true
        background = Color(0, 0, 0, 0)
        focusableWindowState = false
        type = Type.UTILITY
        setBounds(rect)

        shape = Area(Rectangle2D.Float(0f, 0f, rect.width.toFloat(), rect.height.toFloat())).apply {
            subtract(
                Area(
                    Rectangle2D.Float(
                        borderWidth.toFloat(),
                        borderWidth.toFloat(),
                        max(rect.width - borderWidth * 2, 0).toFloat(),
                        max(rect.height - borderWidth * 2, 0).toFloat()
                    )
                )
            )
        }

        contentPane = object : JComponent() {
            override fun paintComponent(graphics: Graphics) {
                val g2 = graphics as Graphics2D
                g2.color = Color(255, 80, 80, 220)
                g2.fillRect(0, 0, width, borderWidth)
                g2.fillRect(0, height - borderWidth, width, borderWidth)
                g2.fillRect(0, 0, borderWidth, height)
                g2.fillRect(width - borderWidth, 0, borderWidth, height)
            }
        }
    }
}

class ScreenRegionRecognizerController(
    private val appState: AppState,
    private val tileRecognizer: TileRecognizer,
    private val getMainWindowPosition: () -> WindowPosition,
    private val setMainWindowPosition: (WindowPosition) -> Unit,
    private val focusRequiredMessage: String,
    private val noSelectionMessage: String,
) {
    private val logger = LoggerFactory.getLogger("ScreenRegionRecognizerController")
    private val robot = Robot()
    private val screenSize: Dimension = Toolkit.getDefaultToolkit().screenSize

    private var previousMainWindowPosition: WindowPosition? = null
    private var selectionOverlayWindow: SelectionOverlayWindow? = null
    private var selectionBorderWindow: SelectionBorderWindow? = null

    val isSupported = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    val selectionRect = mutableStateOf<Rectangle?>(null)
    val isSelecting = mutableStateOf(false)

    fun beginSelection() {
        if (!isSupported) {
            return
        }
        if (appState.lastTileInputHandler == null) {
            tileRecognizer.coroutineScope.launch {
                appState.snackbarHostState.showSnackbar(focusRequiredMessage)
            }
            return
        }

        closeBorderWindow()
        closeSelectionOverlay()
        previousMainWindowPosition = getMainWindowPosition()
        setMainWindowPosition(WindowPosition(100000.dp, 100000.dp))
        isSelecting.value = true

        selectionOverlayWindow = SelectionOverlayWindow(
            screenSize = screenSize,
            onSelected = { rect ->
                val normalized = rect?.normalize()?.takeIf { it.width > 5 && it.height > 5 }
                if (normalized != null) {
                    selectionRect.value = normalized
                    showBorderWindow(normalized)
                }
                finishSelection()
            },
            onCancel = {
                finishSelection()
            }
        ).also { it.open() }
    }

    fun exitSelection() {
        if (!isSupported) {
            return
        }
        selectionRect.value = null
        closeBorderWindow()
        finishSelection()
    }

    fun recognizeSelection() {
        if (!isSupported) {
            return
        }
        val rect = selectionRect.value
        if (rect == null) {
            tileRecognizer.coroutineScope.launch {
                appState.snackbarHostState.showSnackbar(noSelectionMessage)
            }
            return
        }

        val actionHandler = appState.lastTileInputHandler
        if (actionHandler == null) {
            tileRecognizer.coroutineScope.launch {
                appState.snackbarHostState.showSnackbar(focusRequiredMessage)
            }
            return
        }

        tileRecognizer.coroutineScope.launch {
            closeBorderWindow()
            delay(60)

            try {
                val captured = withContext(Dispatchers.IO) {
                    robot.createScreenCapture(rect)
                }
                val result = tileRecognizer.recognizeFromBitmap(captured.toComposeImageBitmap())
                if (result.isNotEmpty()) {
                    actionHandler(TileImeHostState.ImeAction.Replace(result))
                } else {
                    appState.snackbarHostState.showSnackbar(tileRecognizer.noDetectionMsg)
                }
            } catch (t: Throwable) {
                logger.error("failed to recognize selection", t)
            } finally {
                showBorderWindow(rect)
            }
        }
    }

    fun installKeyDispatcher(): KeyEventDispatcher {
        return KeyEventDispatcher { event ->
            if (!isSupported || event.id != KeyEvent.KEY_PRESSED) {
                return@KeyEventDispatcher false
            }

            when (event.keyCode) {
                KeyEvent.VK_A -> {
                    beginSelection()
                    true
                }

                KeyEvent.VK_D -> {
                    recognizeSelection()
                    true
                }

                KeyEvent.VK_Q, KeyEvent.VK_ESCAPE -> {
                    if (isSelecting.value || selectionRect.value != null) {
                        exitSelection()
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    fun dispose() {
        closeBorderWindow()
        closeSelectionOverlay()
        restoreMainWindowPosition()
    }

    private fun finishSelection() {
        closeSelectionOverlay()
        isSelecting.value = false
        restoreMainWindowPosition()
    }

    private fun restoreMainWindowPosition() {
        previousMainWindowPosition?.let {
            setMainWindowPosition(it)
            previousMainWindowPosition = null
        }
    }

    private fun showBorderWindow(rect: Rectangle) {
        closeBorderWindow()
        selectionBorderWindow = SelectionBorderWindow(rect).also {
            it.isVisible = true
        }
    }

    private fun closeBorderWindow() {
        selectionBorderWindow?.dispose()
        selectionBorderWindow = null
    }

    private fun closeSelectionOverlay() {
        selectionOverlayWindow?.dispose()
        selectionOverlayWindow = null
    }
}

val LocalScreenRegionRecognizerController = compositionLocalOf<ScreenRegionRecognizerController> {
    error("CompositionLocal LocalScreenRegionRecognizerController not present")
}

private fun Rectangle.normalize(): Rectangle {
    val left = min(x, x + width)
    val right = max(x, x + width)
    val top = min(y, y + height)
    val bottom = max(y, y + height)
    return Rectangle(left, top, right - left, bottom - top)
}

fun registerScreenRegionKeyDispatcher(controller: ScreenRegionRecognizerController): AutoCloseable {
    val dispatcher = controller.installKeyDispatcher()
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    return AutoCloseable {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
    }
}
