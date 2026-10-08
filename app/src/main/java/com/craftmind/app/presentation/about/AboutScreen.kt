package com.craftmind.app.presentation.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.craftmind.app.BuildConfig
import com.craftmind.app.designsystem.CraftMindCard
import com.craftmind.app.designsystem.CraftMindDetailLines
import com.craftmind.app.designsystem.CraftMindDivider
import com.craftmind.app.designsystem.CraftMindExpandableSection
import com.craftmind.app.designsystem.CraftMindKeyValueRow
import com.craftmind.app.designsystem.CraftMindLayout
import com.craftmind.app.designsystem.CraftMindNotice
import com.craftmind.app.designsystem.CraftMindScreen
import com.craftmind.app.designsystem.CraftMindSectionHeader
import com.craftmind.app.designsystem.CraftMindStatusBadge
import com.craftmind.app.designsystem.CraftMindTertiaryButton
import com.craftmind.app.designsystem.CraftMindTone
import com.craftmind.app.designsystem.CraftMindType

/**
 * About CraftMind (Phase 15 §11).
 *
 * Reachable from Settings → About. It states only what is true of this build: the product and its developer, the
 * application id and version this APK was built with, the real technology acknowledgements, the licences of the
 * libraries actually used, and the privacy and terms text that also ships on the project website.
 *
 * It deliberately contains no company registration, team roster, office, founding history, customer counts,
 * investors, partnerships, awards, funding, or certification claims, because none of those exist for this project.
 */
@Composable
fun AboutScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    var openWebsiteFailed by remember { mutableStateOf(false) }

    CraftMindScreen(
        title = "About CraftMind",
        eyebrow = "AI Minecraft Builder",
        subtitle = "Product, developer, version, technology, and legal information for this build.",
        modifier = modifier,
        actions = { CraftMindTertiaryButton(text = "Close", onClick = onClose) },
    ) {
        if (openWebsiteFailed) {
            CraftMindNotice(
                tone = CraftMindTone.CAUTION,
                title = "Website not reachable",
                message = "This device could not open the CraftMind website. The site is published from the " +
                    "`website/` directory of this repository; the address below is a deployment target and may not " +
                    "be live yet.",
                onDismiss = { openWebsiteFailed = false },
            )
        }

        CraftMindCard(emphasized = true) {
            Column(verticalArrangement = Arrangement.spacedBy(CraftMindLayout.sm)) {
                Text(
                    text = "CraftMind",
                    style = CraftMindType.display,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "Describe it. Show it. Build it.",
                    style = CraftMindType.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "An AI-powered Minecraft building platform designed to turn natural-language ideas and " +
                        "references into structured Minecraft builds.",
                    style = CraftMindType.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm)) {
                    CraftMindStatusBadge(label = "Version ${BuildConfig.VERSION_NAME}", tone = CraftMindTone.BRAND)
                    CraftMindStatusBadge(label = "BuildPlan v2", tone = CraftMindTone.NEUTRAL)
                }
            }
        }

        CraftMindCard {
            CraftMindSectionHeader(
                eyebrow = "Developer",
                title = "Sarthak Bharambe",
                subtitle = "Design, Android engineering, bridge protocol, and certification testing.",
            )
        }

        CraftMindCard {
            CraftMindSectionHeader(
                eyebrow = "Product",
                title = "What CraftMind does",
                subtitle = "Four steps, each backed by real state in the app.",
            )
            CraftMindDetailLines(
                listOf(
                    "1. You describe a build, and optionally add one image or one supported public video reference.",
                    "2. Your own AI provider key is used to generate a structured BuildPlan; CraftMind has no hosted AI backend.",
                    "3. Every plan is parsed and validated locally before it can be accepted and saved on this device.",
                    "4. Construction runs only through a paired, authenticated Minecraft bridge, after a server " +
                        "preflight and your separate final confirmation.",
                ),
            )
            Text(
                text = "There is no manual block editor: the AI plan is the artefact you review, refine, and build.",
                style = CraftMindType.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CraftMindCard {
            CraftMindSectionHeader(
                eyebrow = "This build",
                title = "Application and version",
                subtitle = "Values read from this installed build, not from marketing copy.",
            )
            CraftMindKeyValueRow(label = "Application ID", value = BuildConfig.APPLICATION_ID)
            CraftMindKeyValueRow(
                label = "Version",
                value = "${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})",
            )
            CraftMindDivider()
            CraftMindKeyValueRow(label = "Minimum Android", value = "Android 8.0 (API 26)")
            CraftMindKeyValueRow(label = "Target Android", value = "API 35")
            CraftMindKeyValueRow(label = "Certified target", value = "Minecraft Java 1.20.1 · Fabric Loader 0.16.10")
            CraftMindKeyValueRow(label = "BuildPlan schema", value = "Version 2")
            CraftMindKeyValueRow(label = "Bridge protocol", value = "Version 2")
            Text(
                text = "Real Minecraft runtime tests have not been performed by this build's verification pipeline; " +
                    "the Minecraft screen shows the certification state actually recorded for each runtime.",
                style = CraftMindType.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CraftMindCard {
            CraftMindSectionHeader(
                eyebrow = "Acknowledgements",
                title = "Technology",
                subtitle = "The libraries this app is really built with.",
            )
            CraftMindDetailLines(
                listOf(
                    "Kotlin and the Kotlin standard library",
                    "Jetpack Compose and Material 3 components",
                    "AndroidX Activity, Lifecycle, ViewModel, DataStore, and Security",
                    "kotlinx.coroutines and kotlinx.serialization",
                    "OkHttp for provider and bridge HTTPS transport",
                    "Gson in the shared bridge protocol module",
                    "Android Keystore for provider credential protection",
                    "Fabric mod toolchain for the Minecraft bridge in `minecraft-bridge/`",
                    "JUnit 4 for the JVM test suites",
                ),
            )
        }

        CraftMindCard {
            CraftMindSectionHeader(
                eyebrow = "Licences",
                title = "Attribution",
                subtitle = "Open-source components retain their own licences.",
            )
            CraftMindDetailLines(
                listOf(
                    "Kotlin, Jetpack Compose, Material 3, AndroidX, kotlinx.coroutines, kotlinx.serialization, " +
                        "OkHttp, and Gson — Apache License 2.0",
                    "JUnit 4 — Eclipse Public License 1.0",
                    "Full licence texts are published with the respective projects.",
                ),
            )
            Text(
                text = "CraftMind ships no Mojang or Minecraft assets: no game textures, models, logos, UI art, or " +
                    "fonts are bundled, copied, or imitated. \"Minecraft\" is used only to name the game this tool " +
                    "builds inside.",
                style = CraftMindType.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CraftMindCard {
            CraftMindSectionHeader(
                eyebrow = "Legal and web",
                title = "Privacy, terms, and website",
                subtitle = "The repository's privacy and terms pages are the source of truth.",
            )
            var privacyExpanded by remember { mutableStateOf(false) }
            CraftMindExpandableSection(
                title = "Privacy",
                summary = "Where your prompt, keys, and build history go",
                expanded = privacyExpanded,
                onToggle = { privacyExpanded = !privacyExpanded },
            ) {
                CraftMindDetailLines(
                    listOf(
                        "Your API key is encrypted with a key in Android Keystore and stored in app-private " +
                            "no-backup storage on this device. It is never displayed, never sent to the Minecraft " +
                            "bridge, and never sent to a CraftMind server.",
                        "When you generate a plan, your prompt and any chosen visual input go directly to your AI " +
                            "provider over HTTPS. The provider's own terms govern its processing and retention.",
                        "For a supported public video reference, this device reads bounded byte ranges and sends at " +
                            "most five sampled frames. The video URL itself is not sent to the model.",
                        "Accepted plans and reference metadata are kept in local history on this device. CraftMind " +
                            "has no account service, no cloud sync, and no analytics; Android app backup is disabled.",
                        "Raw image bytes, video bytes, and sampled frames are never stored in build history.",
                    ),
                )
            }
            var termsExpanded by remember { mutableStateOf(false) }
            CraftMindExpandableSection(
                title = "Terms of use",
                summary = "Draft — requires final legal review before publication",
                expanded = termsExpanded,
                onToggle = { termsExpanded = !termsExpanded },
            ) {
                CraftMindNotice(
                    tone = CraftMindTone.CAUTION,
                    message = "These terms are a draft placeholder. They have not been reviewed by a lawyer and must " +
                        "not be treated as final. No warranty, guarantee of results, or liability position is " +
                        "asserted here.",
                )
                CraftMindDetailLines(
                    listOf(
                        "CraftMind is provided as-is for building inside your own Minecraft worlds.",
                        "You are responsible for your AI provider account, its key, its costs, and its terms.",
                        "You are responsible for the Minecraft servers and worlds you pair with, and for any " +
                            "changes made in them; placed blocks cannot be rolled back by CraftMind.",
                        "No licence to Minecraft, Mojang, or Microsoft property is granted or implied.",
                    ),
                )
            }
            CraftMindDivider()
            CraftMindKeyValueRow(label = "Website source", value = "`website/` in this repository")
            CraftMindKeyValueRow(label = "Deployment target", value = WEBSITE_DEPLOYMENT_TARGET)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.sm, Alignment.End),
            ) {
                CraftMindTertiaryButton(
                    text = "Open website",
                    onClick = {
                        openWebsiteFailed = runCatching { uriHandler.openUri(WEBSITE_DEPLOYMENT_TARGET) }.isFailure
                    },
                )
            }
            Text(
                text = "The website is a static site published from this repository. The address above is its GitHub " +
                    "Pages deployment target; it is not a claim that the site is currently published.",
                style = CraftMindType.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CraftMindCard {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(CraftMindLayout.md),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = CraftMindLayout.xxs)
                        .align(Alignment.Top),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(CraftMindLayout.sm),
                ) {
                    Text(text = "What this build is not", style = CraftMindType.titleMedium)
                    CraftMindDetailLines(
                        listOf(
                            "No account service, subscriptions, pricing, or marketplace.",
                            "No hosted AI backend, no CraftMind servers, no analytics.",
                            "No company registration, team, office, or funding history.",
                            "No customer counts, partnerships, awards, or certifications beyond the runtime " +
                                "certification records shown on the Minecraft screen.",
                            "No manual block editor and no fabricated builds or progress.",
                        ),
                    )
                }
            }
        }
    }
}

/** Matches the deployment target documented in `website/README.md`. */
private const val WEBSITE_DEPLOYMENT_TARGET = "https://cybervault-hacky.github.io/Craftmind-/"
