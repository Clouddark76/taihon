package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.window.DialogProperties
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun <T> MultiSelectListPreferenceWidget(
    values: Set<T>,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    badge: ImageVector? = null,
    entries: Map<out T, String>,
    onValuesChange: (Set<T>) -> Unit,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = title,
        subtitle = subtitle,
        icon = icon,
        badge = badge,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        val selected = remember(values, entries) {
            mutableStateListOf<T>().apply {
                addAll(entries.keys.filter { it in values })
            }
        }

        AlertDialog(
            onDismissRequest = { isDialogShown = false },
            title = { Text(text = title) },
            text = {
                LazyColumn {
                    entries.forEach { (key, label) ->
                        item(key = key) {
                            LabeledCheckbox(
                                label = label,
                                checked = key in selected,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        if (key !in selected) {
                                            selected.add(key)
                                        }
                                    } else {
                                        selected.remove(key)
                                    }
                                },
                            )
                        }
                    }
                }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = true,
            ),
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(
                            onClick = {
                                selected.clear()
                                selected.addAll(entries.keys)
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_select_all))
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        TextButton(
                            onClick = {
                                selected.clear()
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_reset))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Spacer(modifier = Modifier.weight(1f))

                        TextButton(
                            onClick = {
                                isDialogShown = false
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_cancel))
                        }

                        TextButton(
                            onClick = {
                                onValuesChange(selected.toSet())
                                isDialogShown = false
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    }
                }
            },
            dismissButton = null,
        )
    }
}
