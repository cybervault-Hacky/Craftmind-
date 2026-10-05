# CraftMind v1.0.0 — prepared release notes

**Release status:** not published. Version `1.0.0` (`versionCode` `10000`) and application ID `com.craftmind.app` are configured in source. No APK has been built, signed, installed, or attached to a release.

## Intended scope

- Android AI-first Minecraft build planning using the user's own Google Gemini API key. The key is encrypted with Android Keystore-backed AES-GCM storage; requests go directly to Google over HTTPS.
- Provider/model verification before use, structured BuildPlan v2 parsing and validation, explicit review and acceptance, and local immutable plan/refinement history.
- Optional single visual reference: validated JPEG, PNG, or WebP image, or a narrow direct-public-video workflow for range-capable HTTPS MP4/WebM files hosted on `raw.githubusercontent.com`. Video analysis needs a model verified for multi-image vision and sends at most five sampled frames. The source URL remains local history and is not sent to the AI model.
- Optional paired server-only Fabric bridge. Construction is gated by the existing supported-server policy, an accepted plan, server preflight, and a separate final user confirmation. Execution status comes from bridge reports.
- No CraftMind AI backend, account, cloud sync, manual block editor, monetization, ads, or automatic construction.

## Compatibility configured in source

- Android API 26 minimum, target SDK 35.
- Minecraft Java 1.20.1, Java 17, Fabric Loader 0.16.10, Fabric API 0.92.2+1.20.1.
- CraftMind Bridge 1.1.0, protocol 1, BuildPlan schema v2.

These are configured targets, not a verified compatibility matrix. AI plans may be inaccurate, source access/model capabilities vary, the bridge is limited to a narrowly supported server setup, and placed blocks cannot be rolled back by CraftMind.

## Verification still required

Android/Fabric tests and builds, release shrinking/signature inspection, device installation, authenticated provider/image/video smoke tests, Minecraft bridge execution tests, APK checksum publication, and static website deployment have not been verified in this environment. See [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md); no download URL, file size, release date, or checksum is claimed here.
