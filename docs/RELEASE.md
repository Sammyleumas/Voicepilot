# Release preparation

Nothing is published automatically. These are the steps and the items that still need an account owner.

## Versioning

`gradle.properties`: `VERSION_CODE` (increment for every Play upload) and `VERSION_NAME` (semantic version). Debug builds add `.debug` / `-debug`.

## Signing

1. Create an upload key (keep it out of the repository):
   ```bash
   keytool -genkeypair -v -keystore voicepilot-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Create `keystore.properties` in the repository root (git-ignored):
   ```properties
   storeFile=voicepilot-upload.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```
3. Enrol in Play App Signing when creating the app in Play Console.

## Build artifacts

```bash
./gradlew :app:assembleRelease   # signed APK if keystore.properties exists
./gradlew :app:bundleRelease     # AAB for Play: app/build/outputs/bundle/release/
```

Release builds use R8 minification and resource shrinking (`proguard-rules.pro` keeps kotlinx.serialization serializers for core models). Verify a release build on a device before uploading: R8 issues only show at runtime.

## Before submitting to Google Play

- [ ] Privacy policy hosted at a public URL (draft: [PRIVACY_POLICY.md](PRIVACY_POLICY.md)); fill in the controller's legal name and contact.
- [ ] Data safety form: microphone audio processed by the device speech service; command text sent to Google (Gemini) only when AI is enabled; contacts read on-device only; no data sold; data deletion available in-app.
- [ ] Permissions declarations: foreground service type `microphone` (justify hands-free mode with a video), notification listener (Play reviews notification access; describe the opt-in assistant), `SCHEDULE_EXACT_ALARM` (reminders; or remove it and accept inexact reminders).
- [ ] Default assistant role: Play may require a declaration for apps offering `VoiceInteractionService`.
- [ ] Content rating questionnaire, target audience (not designed for children).
- [ ] Store listing: name “VoicePilot AI”, short description, full description, 512×512 icon, 1024×500 feature graphic.
- [ ] Screenshots (phone 1080×1920 or larger, min 2; 7" and 10" tablet optional): onboarding welcome, home idle, home listening with waveform, WhatsApp confirmation card, conversation, history, settings › AI, routines.
- [ ] Test on at least: Android 10, 12, 14/15; one Samsung, one Tecno/Infinix/itel (popular in Nigeria), one Pixel. See [TESTING.md](TESTING.md).
- [ ] Billing products and server-side verification if Premium is sold (see [INTEGRATIONS.md](INTEGRATIONS.md)).
- [ ] Deploy and load-test the backend if VoicePilot Cloud is offered.

## Remaining requirements that need the owner

| Item | Needed from |
|---|---|
| Upload key and Play Console app | Account owner |
| Privacy policy URL, legal entity details | Owner |
| Gemini API key / Google Cloud project for the backend | Owner |
| Firebase project (auth) if using cloud accounts | Owner |
| Play Billing products | Owner |
