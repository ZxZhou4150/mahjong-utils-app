package io.ssttkkl.mahjongutils.app.components.tile

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.WindowPosition
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import io.ssttkkl.mahjongutils.app.base.utils.LoggerFactory
import io.ssttkkl.mahjongutils.app.base.utils.PlatformUtils
import io.ssttkkl.mahjongutils.app.components.appscaffold.AppState
import io.ssttkkl.mahjongutils.app.components.tileime.TileImeHostState
import io.ssttkkl.mahjongutils.app.models.DesktopScreenRegionShortcutOptions
import io.ssttkkl.mahjongutils.app.models.DesktopShortcut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BasicStroke
import java.awt.Color
import java.awt.EventQueue
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
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
    private val virtualBounds: Rectangle,
    private val onSelected: (Rectangle?) -> Unit,
    private val onCancel: () -> Unit,
) : JWindow() {
    private var selectionState: OverlaySelection? = null

    init {
        isAlwaysOnTop = true
        background = Color(0, 0, 0, 1)
        setBounds(virtualBounds)

        val panel = object : JComponent() {
            override fun paintComponent(graphics: Graphics) {
                val g2 = graphics as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
                )

                g2.color = Color(0, 0, 0, 80)
                g2.fillRect(0, 0, width, height)

                selectionState?.toRectangle()?.takeIf { it.width > 0 && it.height > 0 }?.let { rect ->
                    g2.color = Color(255, 255, 255, 32)
                    g2.fillRect(rect.x, rect.y, rect.width, rect.height)
                    g2.color = Color(255, 80, 80, 220)
                    g2.stroke = BasicStroke(2f)
                    g2.drawRect(rect.x, rect.y, rect.width, rect.height)
                }
            }
        }
        contentPane = panel

        val mouseAdapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                selectionState = OverlaySelection(e.x, e.y, e.x, e.y)
                panel.repaint()
            }

            override fun mouseDragged(e: MouseEvent) {
                selectionState = selectionState?.copy(currentX = e.x, currentY = e.y)
                panel.repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                selectionState = selectionState?.copy(currentX = e.x, currentY = e.y)
                panel.repaint()
                onSelected(
                    selectionState?.toRectangle()?.translateBy(virtualBounds.x, virtualBounds.y)
                )
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
    @Suppress("unused")
    private val setMainWindowPosition: (WindowPosition) -> Unit,
    private val getShortcutOptions: () -> DesktopScreenRegionShortcutOptions,
    private val focusRequiredMessage: String,
    private val noSelectionMessage: String,
    private val unsupportedMessage: String,
) {
    private val logger = LoggerFactory.getLogger("ScreenRegionRecognizerController")
    private val robot = Robot()
    private val virtualBounds: Rectangle = getVirtualDesktopBounds()

    private var selectionOverlayWindow: SelectionOverlayWindow? = null
    private var selectionBorderWindow: SelectionBorderWindow? = null

    val isSupported = run {
        val osName = System.getProperty("os.name")
        osName.contains("Windows", ignoreCase = true) || PlatformUtils.isApple
    }
    val selectionRect = mutableStateOf<Rectangle?>(null)
    val isSelecting = mutableStateOf(false)

    fun beginSelection() {
        if (!isSupported) {
            tileRecognizer.coroutineScope.launch {
                appState.snackbarHostState.showSnackbar(unsupportedMessage)
            }
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
        isSelecting.value = true

        selectionOverlayWindow = SelectionOverlayWindow(
            virtualBounds = virtualBounds,
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
            tileRecognizer.coroutineScope.launch {
                appState.snackbarHostState.showSnackbar(unsupportedMessage)
            }
            return
        }
        selectionRect.value = null
        closeBorderWindow()
        finishSelection()
    }

    fun recognizeSelection() {
        if (!isSupported) {
            tileRecognizer.coroutineScope.launch {
                appState.snackbarHostState.showSnackbar(unsupportedMessage)
            }
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

    fun installFocusedKeyDispatcher(): KeyEventDispatcher {
        return KeyEventDispatcher { event ->
            if (!isSupported || event.id != KeyEvent.KEY_PRESSED) {
                return@KeyEventDispatcher false
            }
            handleShortcutEvent(event)
        }
    }

    fun handleShortcutEvent(event: KeyEvent): Boolean {
        val shortcutOptions = getShortcutOptions()
        return when {
            shortcutOptions.startSelection.matches(event) -> {
                beginSelection()
                true
            }

            shortcutOptions.recognizeSelection.matches(event) -> {
                recognizeSelection()
                true
            }

            event.keyCode == KeyEvent.VK_ESCAPE || shortcutOptions.exitSelection.matches(event) -> {
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

    fun dispose() {
        closeBorderWindow()
        closeSelectionOverlay()
    }

    private fun finishSelection() {
        closeSelectionOverlay()
        isSelecting.value = false
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

private fun DesktopShortcut.matches(event: KeyEvent): Boolean {
    return keyToAwtCode(key) == event.keyCode
            && ctrl == event.isControlDown
            && alt == event.isAltDown
            && shift == event.isShiftDown
            && meta == event.isMetaDown
}

private fun DesktopShortcut.toWindowsModifiers(): Int {
    var modifiers = 0
    if (alt) modifiers = modifiers or WinUser.MOD_ALT
    if (ctrl) modifiers = modifiers or WinUser.MOD_CONTROL
    if (shift) modifiers = modifiers or WinUser.MOD_SHIFT
    if (meta) modifiers = modifiers or WinUser.MOD_WIN
    return modifiers
}

private fun Rectangle.translateBy(dx: Int, dy: Int): Rectangle =
    Rectangle(x + dx, y + dy, width, height)

private fun Rectangle.normalize(): Rectangle {
    val left = min(x, x + width)
    val right = max(x, x + width)
    val top = min(y, y + height)
    val bottom = max(y, y + height)
    return Rectangle(left, top, right - left, bottom - top)
}

private fun getVirtualDesktopBounds(): Rectangle {
    var left = Int.MAX_VALUE
    var top = Int.MAX_VALUE
    var right = Int.MIN_VALUE
    var bottom = Int.MIN_VALUE

    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.forEach { device ->
        val bounds = device.defaultConfiguration.bounds
        left = min(left, bounds.x)
        top = min(top, bounds.y)
        right = max(right, bounds.x + bounds.width)
        bottom = max(bottom, bounds.y + bounds.height)
    }

    return Rectangle(left, top, right - left, bottom - top)
}

private fun keyToAwtCode(key: String): Int = when (key) {
    "ESCAPE" -> KeyEvent.VK_ESCAPE
    "SPACE" -> KeyEvent.VK_SPACE
    "0" -> KeyEvent.VK_0
    "1" -> KeyEvent.VK_1
    "2" -> KeyEvent.VK_2
    "3" -> KeyEvent.VK_3
    "4" -> KeyEvent.VK_4
    "5" -> KeyEvent.VK_5
    "6" -> KeyEvent.VK_6
    "7" -> KeyEvent.VK_7
    "8" -> KeyEvent.VK_8
    "9" -> KeyEvent.VK_9
    "A" -> KeyEvent.VK_A
    "B" -> KeyEvent.VK_B
    "C" -> KeyEvent.VK_C
    "D" -> KeyEvent.VK_D
    "E" -> KeyEvent.VK_E
    "F" -> KeyEvent.VK_F
    "G" -> KeyEvent.VK_G
    "H" -> KeyEvent.VK_H
    "I" -> KeyEvent.VK_I
    "J" -> KeyEvent.VK_J
    "K" -> KeyEvent.VK_K
    "L" -> KeyEvent.VK_L
    "M" -> KeyEvent.VK_M
    "N" -> KeyEvent.VK_N
    "O" -> KeyEvent.VK_O
    "P" -> KeyEvent.VK_P
    "Q" -> KeyEvent.VK_Q
    "R" -> KeyEvent.VK_R
    "S" -> KeyEvent.VK_S
    "T" -> KeyEvent.VK_T
    "U" -> KeyEvent.VK_U
    "V" -> KeyEvent.VK_V
    "W" -> KeyEvent.VK_W
    "X" -> KeyEvent.VK_X
    "Y" -> KeyEvent.VK_Y
    "Z" -> KeyEvent.VK_Z
    "F1" -> KeyEvent.VK_F1
    "F2" -> KeyEvent.VK_F2
    "F3" -> KeyEvent.VK_F3
    "F4" -> KeyEvent.VK_F4
    "F5" -> KeyEvent.VK_F5
    "F6" -> KeyEvent.VK_F6
    "F7" -> KeyEvent.VK_F7
    "F8" -> KeyEvent.VK_F8
    "F9" -> KeyEvent.VK_F9
    "F10" -> KeyEvent.VK_F10
    "F11" -> KeyEvent.VK_F11
    "F12" -> KeyEvent.VK_F12
    else -> error("unsupported shortcut key: $key")
}

private class WindowsGlobalHotKeyDispatcher(
    private val controller: ScreenRegionRecognizerController,
    private val getShortcutOptions: () -> DesktopScreenRegionShortcutOptions,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger("WindowsGlobalHotKeyDispatcher")
    private val user32 = User32.INSTANCE
    private val kernel32 = Kernel32.INSTANCE
    private var threadId = 0
    private val thread = object : Thread("screen-region-hotkeys") {
        override fun run() {
            threadId = kernel32.GetCurrentThreadId()

            val registrations = buildRegistrations(getShortcutOptions())
            registrations.forEach { registration ->
                val success = user32.RegisterHotKey(
                    null,
                    registration.id,
                    registration.shortcut.toWindowsModifiers(),
                    keyToAwtCode(registration.shortcut.key)
                )
                if (!success) {
                    logger.warn("failed to register hotkey id=${registration.id} shortcut=${registration.shortcut}")
                }
            }

            val msg = WinUser.MSG()
            while (user32.GetMessage(msg, null, 0, 0) != 0) {
                if (msg.message == WinUser.WM_HOTKEY) {
                    val registration = registrations.firstOrNull { it.id == msg.wParam.toInt() } ?: continue
                    EventQueue.invokeLater {
                        when (registration.action) {
                            ShortcutAction.StartSelection -> controller.beginSelection()
                            ShortcutAction.RecognizeSelection -> controller.recognizeSelection()
                            ShortcutAction.ExitSelection -> controller.exitSelection()
                        }
                    }
                }
            }

            registrations.forEach {
                user32.UnregisterHotKey(null, it.id)
            }
        }
    }

    init {
        thread.isDaemon = true
        thread.start()
    }

    override fun close() {
        if (threadId != 0) {
            user32.PostThreadMessage(threadId, WinUser.WM_QUIT, null, null)
        }
    }
}

private enum class ShortcutAction {
    StartSelection,
    RecognizeSelection,
    ExitSelection,
}

private data class HotKeyRegistration(
    val id: Int,
    val action: ShortcutAction,
    val shortcut: DesktopShortcut,
)

private fun buildRegistrations(options: DesktopScreenRegionShortcutOptions): List<HotKeyRegistration> = listOf(
    HotKeyRegistration(1, ShortcutAction.StartSelection, options.startSelection),
    HotKeyRegistration(2, ShortcutAction.RecognizeSelection, options.recognizeSelection),
    HotKeyRegistration(3, ShortcutAction.ExitSelection, options.exitSelection),
)

val LocalScreenRegionRecognizerController = compositionLocalOf<ScreenRegionRecognizerController> {
    error("CompositionLocal LocalScreenRegionRecognizerController not present")
}

fun registerScreenRegionKeyDispatcher(
    controller: ScreenRegionRecognizerController,
    getShortcutOptions: () -> DesktopScreenRegionShortcutOptions,
): AutoCloseable {
    if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        return WindowsGlobalHotKeyDispatcher(controller, getShortcutOptions)
    }

    val dispatcher = controller.installFocusedKeyDispatcher()
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    return AutoCloseable {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
    }
}
