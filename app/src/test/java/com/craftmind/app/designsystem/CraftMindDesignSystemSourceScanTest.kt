package com.craftmind.app.designsystem

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 15 §3 and §21: the design system is the only place allowed to hard-code visual literals.
 *
 * Future phases must restyle the app by changing tokens, not by scattering raw colours or corner radii through
 * screens, so this scan fails the build when a production source outside `designsystem/` reintroduces them.
 */
class CraftMindDesignSystemSourceScanTest {
    private val sourceRoot = File("src/main/java")

    @Test
    fun productionSourcesOutsideTheDesignSystemCarryNoRawColorsOrRadii() {
        assertTrue(
            "expected the app source root at ${sourceRoot.absolutePath} (run from the app module directory)",
            sourceRoot.isDirectory,
        )
        val scanned = mutableListOf<File>()
        val violations = sourceRoot.walkTopDown()
            .filter { file ->
                file.isFile && file.extension == "kt" && !file.path.contains("/designsystem/")
            }
            .flatMap { file ->
                scanned.add(file)
                file.readLines().asSequence().withIndex().mapNotNull { (index, line) ->
                    val reason = when {
                        RAW_COLOR.containsMatchIn(line) -> "raw Color(0x…) literal"
                        RAW_RADIUS.containsMatchIn(line) -> "raw RoundedCornerShape(<n>.dp) radius"
                        else -> null
                    }
                    reason?.let { "${file.path}:${index + 1}: $it — use a CraftMind token" }
                }
            }
            .toList()

        assertTrue("no production Kotlin sources were scanned", scanned.isNotEmpty())
        assertTrue(
            "design-system values must come from tokens:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun theTokenFilesStayPureKotlinAndTheThemeIsTheOnlyComposeConverter() {
        val tokenFiles = listOf(
            "designsystem/CraftMindColorTokens.kt",
            "designsystem/CraftMindLayoutTokens.kt",
            "designsystem/CraftMindMotionTokens.kt",
            "designsystem/CraftMindTypeTokens.kt",
            "designsystem/CraftMindContrast.kt",
        )
        for (path in tokenFiles) {
            val file = File(sourceRoot, "$PACKAGE_PATH/$path")
            assertTrue("missing token file $PACKAGE_PATH/$path", file.isFile)
            assertFalse(
                "$path must stay pure Kotlin (no androidx imports) so the JVM harness can test it",
                COMPOSE_IMPORT.containsMatchIn(file.readText()),
            )
        }

        val theme = File(sourceRoot, "$PACKAGE_PATH/designsystem/CraftMindTheme.kt")
        assertTrue("missing the Compose token converter", theme.isFile)
        val themeSource = theme.readText()
        assertTrue(
            "CraftMindTheme.kt must expose the token-backed Compose values",
            themeSource.contains("CraftMindShapes") && themeSource.contains("CraftMindLayout"),
        )
    }

    private companion object {
        /** Package directory of the production sources, relative to [sourceRoot]. */
        const val PACKAGE_PATH = "com/craftmind/app"

        /** A real Compose dependency can only enter a file through an import; doc comments may name it. */
        val COMPOSE_IMPORT = Regex("""^\s*import\s+androidx\.""", RegexOption.MULTILINE)

        val RAW_COLOR = Regex("""Color\(0x""")
        val RAW_RADIUS = Regex("""RoundedCornerShape\(\s*\d+(\.\d+)?\.dp""")
    }
}
