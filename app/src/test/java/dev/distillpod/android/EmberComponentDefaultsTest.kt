package dev.distillpod.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Material 3 1.4 changed the default colours of these components; the approved Ember look pins the earlier
 * ones in `ui/theme/DistillComponents.kt`. A file that calls one of them must import the Ember wrapper, or
 * it silently picks up the Material default (grey outlined buttons, dim chip borders).
 */
class EmberComponentDefaultsTest {
    private val pinned = listOf("Button", "OutlinedButton", "TextButton", "FilterChip")

    @Test fun uiFilesUseTheEmberComponentWrappers() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" && it.name != "DistillComponents.kt" }.toList()
        check(sources.size > 20) { "Run from the app module so sources are found" }
        val missing = sources.flatMap { file ->
            val text = file.readText()
            val code = text.replace(Regex("//[^\n]*|/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            pinned.filter { name ->
                Regex("(?<![A-Za-z0-9_.])$name\\s*\\(").containsMatchIn(code) &&
                    !text.contains("import dev.distillpod.android.ui.theme.$name\n")
            }.map { "${file.name}: $it" }
        }
        assertEquals("Import the Ember wrappers from dev.distillpod.android.ui.theme", emptyList<String>(), missing)
    }
}
