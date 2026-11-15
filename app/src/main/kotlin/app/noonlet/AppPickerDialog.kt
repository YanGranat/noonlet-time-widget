package app.noonlet

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture

internal object AppPickerDialog {
    private val handler = Handler(Looper.getMainLooper())

    fun show(activity: Activity, ui: EditorUi, title: String, current: String, isCurrent: () -> Boolean, changed: (String) -> Unit) {
        val entries = mutableListOf<AppPickerCatalog.Entry>()
        val adapter = AppDialogBuilder.ChoiceAdapter(activity, emptyArray<CharSequence>(), true, -1)
        adapter.replaceLabels(listOf(ui.tr(R.string.loading_apps)), false)
        val dialog =
            AppDialogBuilder(activity)
                .setTitle(title)
                .setAdapter(
                    adapter,
                    { choice, item ->
                        if (
                            (!(choice is Dialog) ||
                                !choice.isShowing ||
                                activity.isDestroyed ||
                                activity.isFinishing ||
                                !isCurrent() ||
                                item < 0 ||
                                item >= entries.size)
                        )
                            return@setAdapter
                        val action = entries.get(item).action
                        choice.dismiss()
                        changed(action)
                    },
                )
                .setNegativeButton(ui.tr(R.string.cancel), null)
                .create()
        val manager = activity.applicationContext.packageManager
        val appLocale = ui.locale()
        dialog.show()
        CompletableFuture.supplyAsync({ AppPickerCatalog.load(manager, appLocale) })
            .whenComplete(
                { apps, failure ->
                    handler.post(
                        {
                            if (
                                (activity.isDestroyed ||
                                    activity.isFinishing ||
                                    !dialog.isShowing ||
                                    !isCurrent())
                            )
                                return@post
                            if (failure != null) {
                                adapter.replaceLabels(
                                    listOf(
                                        ui.tr(R.string.apps_could_not_be_loaded),
                                    ),
                                    false,
                                )
                                return@post
                            }
                            entries.addAll(apps)
                            if (entries.isEmpty()) {
                                adapter.replaceLabels(
                                    listOf(
                                        ui.tr(R.string.no_launchable_apps_found),
                                    ),
                                    false,
                                )
                                return@post
                            }
                            adapter.replaceLabels(entries.map { it.label }, true,
                                entries.indexOfFirst { it.action == current })
                        },
                    )
                },
            )
    }

}
