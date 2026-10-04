# CraftMind

**AI Minecraft Builder** · *Describe it. Show it. Build it.*

CraftMind is an Android app concept where a user describes a Minecraft build, optionally adds a local reference image or a public URL, and AI creates the build plan. The product does not manually design block layouts.

## Intended pipeline

**User request → AI-generated BuildPlan → validation → user review → Minecraft execution**

The AI is responsible for creating the build plan. Minecraft only executes a validated AI-generated plan.

## Current status: Phase 1 — Foundation/UI

This repository contains the initial Kotlin, Jetpack Compose, and Material 3 Android foundation, request validation and preview UI, navigation, persisted appearance preference, and provider/Minecraft contracts.

Not implemented in Phase 1:

- AI generation or provider integrations
- Image analysis (images can only be selected and previewed locally)
- URL analysis or fetching (URLs receive syntax validation only)
- Minecraft pairing, connection, or block execution
- API-key entry or credential storage (the secure credential interface is contract-only)

No prompt, image, URL, or credential is sent to a server. There is no CraftMind backend, analytics, or network integration in this phase. Pressing **Generate with AI** validates a request locally and explains that AI generation is not available yet; it does not create a fake plan.

## Project

- Application ID: `com.craftmind.app`
- Android: Kotlin, Jetpack Compose, Material 3
- Architecture: `presentation/`, `domain/`, `data/`, and `designsystem/` boundaries within the app module
- Minimum Android version: API 26; compile/target SDK: 35
- Java toolchain: 17

Build with a JDK 17 installation and Android SDK 35:

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```
