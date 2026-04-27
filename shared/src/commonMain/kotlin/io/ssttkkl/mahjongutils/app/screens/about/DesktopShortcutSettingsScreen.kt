package io.ssttkkl.mahjongutils.app.screens.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ssttkkl.mahjongutils.app.base.components.ComboBox
import io.ssttkkl.mahjongutils.app.base.components.ComboOption
import io.ssttkkl.mahjongutils.app.base.components.ScrollBox
import io.ssttkkl.mahjongutils.app.base.components.SwitchItem
import io.ssttkkl.mahjongutils.app.components.appscaffold.NoParamUrlNavigationScreen
import io.ssttkkl.mahjongutils.app.models.AppOptions
import io.ssttkkl.mahjongutils.app.models.DesktopScreenRegionShortcutOptions
import io.ssttkkl.mahjongutils.app.models.DesktopShortcut
import io.ssttkkl.mahjongutils.app.models.desktopShortcutKeyOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import mahjongutils.composeapp.generated.resources.Res
import mahjongutils.composeapp.generated.resources.label_restore_default
import mahjongutils.composeapp.generated.resources.label_save
import mahjongutils.composeapp.generated.resources.text_screen_region_shortcut_action_exit
import mahjongutils.composeapp.generated.resources.text_screen_region_shortcut_action_recognize
import mahjongutils.composeapp.generated.resources.text_screen_region_shortcut_action_select
import mahjongutils.composeapp.generated.resources.text_screen_region_shortcut_conflict
import mahjongutils.composeapp.generated.resources.text_screen_region_shortcut_desc
import mahjongutils.composeapp.generated.resources.text_screen_region_shortcut_exit_hint
import mahjongutils.composeapp.generated.resources.text_screen_region_shortcut_mod_alt
import mahjongutils.composeapp.generated.resources.text_screen_region_shortcut_mod_ctrl
import mahjongutils.composeapp.generated.resources.text_screen_region_shortcut_mod_meta
import mahjongutils.composeapp.generated.resources.text_screen_region_shortcut_mod_shift
import mahjongutils.composeapp.generated.resources.title_screen_region_shortcuts
import org.jetbrains.compose.resources.stringResource

object DesktopShortcutSettingsScreen : NoParamUrlNavigationScreen() {
    override val path: String
        get() = "about/desktop-shortcuts"

    override val title: String
        @Composable
        get() = stringResource(Res.string.title_screen_region_shortcuts)

    @Composable
    override fun ScreenContent() {
        val coroutineScope = rememberCoroutineScope()
        var options by remember { mutableStateOf(DesktopScreenRegionShortcutOptions.Default) }

        LaunchedEffect(Unit) {
            AppOptions.datastore.data.collectLatest {
                options = it.desktopScreenRegionShortcutOptions
            }
        }

        val scrollState = rememberScrollState()

        ScrollBox(scrollState) {
            Column(
                Modifier.verticalScroll(scrollState).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.title_screen_region_shortcuts)) },
                    supportingContent = {
                        Text(stringResource(Res.string.text_screen_region_shortcut_desc))
                    }
                )

                ShortcutEditor(
                    title = stringResource(Res.string.text_screen_region_shortcut_action_select),
                    shortcut = options.startSelection,
                    onShortcutChange = { options = options.copy(startSelection = it) }
                )
                ShortcutEditor(
                    title = stringResource(Res.string.text_screen_region_shortcut_action_recognize),
                    shortcut = options.recognizeSelection,
                    onShortcutChange = { options = options.copy(recognizeSelection = it) }
                )
                ShortcutEditor(
                    title = stringResource(Res.string.text_screen_region_shortcut_action_exit),
                    desc = stringResource(Res.string.text_screen_region_shortcut_exit_hint),
                    shortcut = options.exitSelection,
                    onShortcutChange = { options = options.copy(exitSelection = it) }
                )

                if (options.hasConflict()) {
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.text_screen_region_shortcut_conflict)) }
                    )
                }

                ListItem(
                    headlineContent = {
                        TextButton(
                            onClick = {
                                options = DesktopScreenRegionShortcutOptions.Default
                            }
                        ) {
                            Text(stringResource(Res.string.label_restore_default))
                        }
                    },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    if (!options.hasConflict()) {
                                        AppOptions.datastore.updateData {
                                            it.copy(desktopScreenRegionShortcutOptions = options)
                                        }
                                    }
                                }
                            },
                            enabled = !options.hasConflict()
                        ) {
                            Text(stringResource(Res.string.label_save))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ShortcutEditor(
    title: String,
    shortcut: DesktopShortcut,
    onShortcutChange: (DesktopShortcut) -> Unit,
    desc: String? = null,
) {
    val keyOptions = desktopShortcutKeyOptions.map { ComboOption(text = it.label, value = it.value) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(title)
        desc?.let {
            Text(it, Modifier.padding(top = 4.dp, bottom = 8.dp))
        }

        ComboBox(
            selected = shortcut.key,
            onSelected = { onShortcutChange(shortcut.copy(key = it)) },
            options = keyOptions,
            modifier = Modifier.fillMaxWidth()
        )

        SwitchItem(
            shortcut.ctrl,
            { onShortcutChange(shortcut.copy(ctrl = it)) },
            stringResource(Res.string.text_screen_region_shortcut_mod_ctrl),
        )
        SwitchItem(
            shortcut.alt,
            { onShortcutChange(shortcut.copy(alt = it)) },
            stringResource(Res.string.text_screen_region_shortcut_mod_alt),
        )
        SwitchItem(
            shortcut.shift,
            { onShortcutChange(shortcut.copy(shift = it)) },
            stringResource(Res.string.text_screen_region_shortcut_mod_shift),
        )
        SwitchItem(
            shortcut.meta,
            { onShortcutChange(shortcut.copy(meta = it)) },
            stringResource(Res.string.text_screen_region_shortcut_mod_meta),
        )
    }
}
