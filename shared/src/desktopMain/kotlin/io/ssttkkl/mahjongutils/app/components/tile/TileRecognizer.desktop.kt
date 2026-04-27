package io.ssttkkl.mahjongutils.app.components.tile

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import com.attafitamim.krop.core.crop.ImageCropper
import com.attafitamim.krop.core.crop.rememberImageCropper
import io.ssttkkl.mahjongutils.app.base.components.ImageCropperDialog
import io.ssttkkl.mahjongutils.app.base.utils.toBufferedImage
import io.ssttkkl.mahjongutils.app.components.appscaffold.AppState
import io.ssttkkl.mahjongutils.app.components.appscaffold.LocalMainWindowState
import io.ssttkkl.mahjongutils.app.components.tileime.TileImeHostState
import io.ssttkkl.mahjongutils.app.models.AppOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mahjongutils.composeapp.generated.resources.Res
import mahjongutils.composeapp.generated.resources.icon_screenshot_frame
import mahjongutils.composeapp.generated.resources.label_recognize_from_screenshot
import mahjongutils.composeapp.generated.resources.text_screen_region_hotkey_register_failed
import mahjongutils.composeapp.generated.resources.text_screen_region_recognizer_failed
import mahjongutils.composeapp.generated.resources.text_screen_region_recognizer_focus_input_first
import mahjongutils.composeapp.generated.resources.text_screen_region_recognizer_select_area_first
import mahjongutils.composeapp.generated.resources.text_screen_region_recognizer_unsupported
import mahjongutils.composeapp.generated.resources.title_crop_image
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

actual class TileRecognizer actual constructor(
    cropper: ImageCropper,
    snackbarHostState: SnackbarHostState,
    noDetectionMsg: String
) : BaseTileRecognizer(cropper, snackbarHostState, noDetectionMsg) {

    @Composable
    actual override fun TileFieldRecognizeImageMenuItems(
        expanded: Boolean,
        onAction: (TileImeHostState.ImeAction) -> Unit,
        onDismissRequest: () -> Unit
    ) {
        super.TileFieldRecognizeImageMenuItems(expanded, onAction, onDismissRequest)
        CaptureMenuItem(onAction, onDismissRequest)
    }

    @Composable
    fun CaptureMenuItem(
        onAction: (TileImeHostState.ImeAction) -> Unit,
        onDismissRequest: () -> Unit
    ) {
        val screenRegionRecognizerController = LocalScreenRegionRecognizerController.current

        val curOnDismissRequest by rememberUpdatedState(onDismissRequest)

        DropdownMenuItem(
            text = {
                Row(Modifier.padding(vertical = 8.dp)) {
                    Icon(vectorResource(Res.drawable.icon_screenshot_frame), "")
                    Text(
                        stringResource(Res.string.label_recognize_from_screenshot),
                        Modifier.padding(horizontal = 8.dp)
                    )
                }
            },
            onClick = {
                curOnDismissRequest()
                screenRegionRecognizerController.beginSelection()
            }
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    actual override suspend fun clipboardHasImage(clipboard: Clipboard): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val transferable =
                    clipboard.getClipEntry()?.asAwtTransferable ?: return@withContext false

                if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    val fileList =
                        transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    val imgFile = fileList.firstOrNull {
                        Files.probeContentType(it.toPath()).startsWith("image/")
                    }

                    return@withContext imgFile != null
                } else if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    return@withContext true
                } else {
                    return@withContext false
                }
            } catch (t: Throwable) {
                logger.error("failed to readClipboardImage", t)
                return@withContext false
            }
        }

    @OptIn(ExperimentalComposeUiApi::class)
    actual override suspend fun readClipboardImage(clipboard: Clipboard): ImageBitmap? =
        withContext(Dispatchers.IO) {
            try {
                var image: ImageBitmap? = null

                val transferable =
                    clipboard.getClipEntry()?.asAwtTransferable ?: return@withContext null

                if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    val fileList =
                        transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    val imgFile = fileList.firstOrNull {
                        Files.probeContentType(it.toPath()).startsWith("image/")
                    }

                    if (imgFile != null) {
                        image = ImageIO.read(imgFile)
                            .toBufferedImage().toComposeImageBitmap()
                    }
                } else if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    image = (transferable.getTransferData(DataFlavor.imageFlavor) as Image)
                        .toBufferedImage().toComposeImageBitmap()
                }

                return@withContext image
            } catch (t: Throwable) {
                logger.error("failed to readClipboardImage", t)
                return@withContext null
            }
        }
}

@Composable
actual fun TileRecognizerHost(
    appState: AppState,
    content: @Composable () -> Unit
) {
    val cropper = rememberImageCropper()
    val tileRecognizer =
        rememberTileRecognizer(cropper, appState.snackbarHostState)
    val appOptions by AppOptions.datastore.data.collectAsState(AppOptions())
    val mainWindowState = LocalMainWindowState.current
    val focusRequiredMessage = stringResource(Res.string.text_screen_region_recognizer_focus_input_first)
    val noSelectionMessage = stringResource(Res.string.text_screen_region_recognizer_select_area_first)
    val unsupportedMessage = stringResource(Res.string.text_screen_region_recognizer_unsupported)
    val recognitionFailedMessage = stringResource(Res.string.text_screen_region_recognizer_failed)
    val hotkeyRegistrationFailedMessage =
        stringResource(Res.string.text_screen_region_hotkey_register_failed)
    val screenRegionRecognizerController = remember(
        appState,
        tileRecognizer,
        mainWindowState,
        appOptions.desktopScreenRegionShortcutOptions,
        focusRequiredMessage,
        noSelectionMessage,
        unsupportedMessage,
        recognitionFailedMessage,
        hotkeyRegistrationFailedMessage,
    ) {
        ScreenRegionRecognizerController(
            appState = appState,
            tileRecognizer = tileRecognizer,
            getMainWindowPosition = { mainWindowState.position },
            setMainWindowPosition = { mainWindowState.position = it },
            getShortcutOptions = { appOptions.desktopScreenRegionShortcutOptions },
            focusRequiredMessage = focusRequiredMessage,
            noSelectionMessage = noSelectionMessage,
            unsupportedMessage = unsupportedMessage,
            recognitionFailedMessage = recognitionFailedMessage,
            hotkeyRegistrationFailedMessage = hotkeyRegistrationFailedMessage,
        )
    }

    DisposableEffect(screenRegionRecognizerController) {
        val keyDispatcherRegistration = registerScreenRegionKeyDispatcher(
            screenRegionRecognizerController
        ) {
            appOptions.desktopScreenRegionShortcutOptions
        }
        onDispose {
            keyDispatcherRegistration.close()
            screenRegionRecognizerController.dispose()
        }
    }

    CompositionLocalProvider(
        LocalTileRecognizer provides tileRecognizer,
        LocalScreenRegionRecognizerController provides screenRegionRecognizerController
    ) {
        content()
    }
    cropper.cropState?.let { cropState ->
        Window(
            onCloseRequest = { cropState.done(accept = false) },
            title = stringResource(Res.string.title_crop_image)
        ) {
            ImageCropperDialog(cropState)
        }
    }
}
