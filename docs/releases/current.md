DistillPod 0.11.0-alpha.1 turns saved moments into ready-to-share discussions and researched infographics.

- Open a prepared saved moment, tap **Create post**, and choose **Quote & discussion** or **Infographic**. Generation begins immediately; the result screen centers on **Share**, with an optional natural-language **Change** instruction.
- Quote discussions use a verified quotation from the saved excerpt, thoughtful paragraphs and podcast attribution. Infographics combine web research, claim-level references and original AI-designed vector illustrations in a 1080 × 1350 PNG.
- The existing ChatGPT connection supports web research but does not support the hosted image-generation tool. Illustrations are designed by the text model and rendered locally; no separate API key or paid-provider fallback is used.
- Generation continues in the background. Canceled, interrupted or failed changes preserve the last completed version. Undo and direct discussion editing are available through the result menu. Existing drafts and source snapshots remain accessible.
- Image results and their previous versions are included in bounded library backups with integrity checks. Posts remain local and are not uploaded by library sync. Native sharing opens the receiving app; finish publication there and copy the description separately if needed.
- Retains optional online transcript lookup, library sync, playback and the existing saved-moment workflow.

This personal-alpha release retains the existing signing certificate and Firebase client configuration. Paid content sync remains disabled. Install over the compatible alpha app; do not uninstall. Android 10+; ARM64 and x86_64. Version code: 20.

Validation before publishing: 276 JVM tests, release lint/builds, large-text emulator acceptance and three live public English/Portuguese output samples. The release workflow additionally gates publication on native, Firebase and isolated Android acceptance checks. See the [design](https://github.com/andrepaim/distillpod/blob/main/docs/android/social-sharing-redesign.md) and [validation record](https://github.com/andrepaim/distillpod/blob/main/docs/android/evidence/social-redesign/validation.md). Actual publication to LinkedIn/Instagram is user-driven and was not performed by the tests.
