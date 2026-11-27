# Google Play listing

Everything to paste into Google Play Console for DistillPod, in the order the console asks for it, with the reasoning behind each answer. Prepared 2026-11-27 against the Play Console help pages of that date; re-check the linked pages before submitting, because several rules changed during 2026.

Nothing here has been submitted. The app as released today (0.38.1-alpha.1) cannot be submitted yet: see [Before the first upload](#before-the-first-upload).

Placeholders the owner must fill are written `[LIKE THIS]`. Developer name on the listing: Andre Paim Lemos. The work below is tracked in [#326](https://github.com/andrepaim/distillpod/issues/326).

## Positioning

One idea, used everywhere: **DistillPod is the AI podcast player that runs on your own ChatGPT account.** The user signs in with ChatGPT and the AI features use the plan they already have. The name "ChatGPT" appears as plain descriptive text only: never in the app title, never in the icon, never as a logo, and always with the non-affiliation line.

## Before the first upload

These are changes to the app or decisions only the owner can make. Sources are linked in [Rules checked](#rules-checked).

| # | What | Why | Kind |
| --- | --- | --- | --- |
| 1 | Remove YouTube stream extraction and download from the Play build | Play's Device and Network Abuse policy forbids using a service against its terms, and YouTube's terms forbid downloading and automated access. This is the most likely cause of rejection or later removal | App change, owner decision, #319 |
| 2 | Remove the GitHub update check and APK download link from the Play build | A Play app "may not modify, replace, or update itself using any method other than Google Play's update mechanism" | App change, #320 |
| 3 | Raise `targetSdk` to 36 | Required for new apps since 31 Aug 2026; `targetSdk` 35 is refused at upload | App change, #321 |
| 4 | Build an Android App Bundle (`.aab`) signed with an upload key | New apps must be uploaded as bundles with Play App Signing | Build change, #322 |
| 5 | Decide the app signing key | Uploading the existing personal-alpha key (PEPK tool, only possible before the first open-testing or production release) keeps one identity across GitHub and Play. A Google-generated key means GitHub installs can never update from Play | Owner decision |
| 6 | Account deletion for library sync, or leave sync out of the Play build | An app that lets users create an account needs an in-app deletion path and a web page for deletion requests. Cloud account erasure is not implemented | App change or owner decision, #323 |
| 7 | A way to report an AI result from inside the app | Play's AI-Generated Content policy requires in-app reporting for apps that generate content with AI. Summaries alone may be exempt; episode chat and spoken answers probably are not | App change, #324 |
| 8 | A privacy policy link inside the app that opens the hosted policy | Required in the app as well as on the listing | App change, #325 |
| 9 | Reviewer access to the AI features | Reviewers expect working sign-in details. A ChatGPT account is the user's own; either supply a dedicated test account or rely on the note that playback, transcription and saving work without sign-in | Owner decision |
| 10 | Foreground service declarations with one short video per type | `mediaPlayback`, `dataSync`, `mediaProcessing` and `microphone` each need a description and a video of the user starting the feature. Play describes `mediaProcessing` as media transcoding; on-device transcription may be questioned | Declarations, owner records videos |
| 11 | Public contact email and developer name | Decided: `andrepaim@gmail.com`, Andre Paim Lemos. A personal account also shows the legal name and country | Done |
| 12 | Keep the app free and open source, or ask OpenAI first | OpenAI documents ChatGPT plan usage for open-source and locally hosted apps; paid or hosted apps go through an interest form. The documented sign-in redirect is a loopback address on a computer; mobile apps are not mentioned | Owner decision |

The published GitHub alpha can continue unchanged next to a Play build.

## Steps in the console

1. **Open a developer account** at <https://play.google.com/console/signup>. Personal account, one-time US$25. Google asks for identity documents, verifies the contact email and phone by code, and asks to verify a physical Android phone with the Play Console app. A free app needs no merchant or tax profile and no D-U-N-S number.
2. **Create app.** Name `DistillPod: AI Podcast Player`, default language English (United States), App, Free. The package name comes from the first bundle uploaded and can never change.
3. **Dashboard → Set up your app.** Fill each item under [App content](#app-content).
4. **Grow → Store presence → Main store listing.** Paste the texts under [Main store listing](#main-store-listing) and upload the images.
5. **Store settings.** Category and contact details under [Store settings](#store-settings).
6. **Release → Setup → App signing.** Make the signing-key choice (row 5 above) before any release leaves internal testing.
7. **Testing → Internal testing.** Upload the first bundle, add yourself, install from Play and check sign-in, playback and transcription.
8. **Testing → Closed testing.** New personal accounts must run a closed test with at least 12 testers opted in for 14 days in a row before production is available. A tester who leaves and returns starts again.
9. **Apply for production access** (Dashboard). Google asks how testers were recruited, what they tested, what changed because of it, who the app is for and how many installs are expected in the first year. Review usually takes up to 7 days.
10. **Production.** Create the release, choose countries, start with a staged rollout. First reviews take from hours to about a week.

The bundles for steps 7 to 10 come from the **Play bundle** workflow, signed with the upload key; see [Google Play bundle](../../docs/releases.md#google-play-bundle) for the key setup, the run and the local check before uploading.

Expect three to five weeks from a ready build to a public listing, most of it the 14-day test.

## Main store listing

| Field | Limit | Value |
| --- | --- | --- |
| App name | 30 | `DistillPod: AI Podcast Player` (29) |
| Short description | 80 | `AI podcast player that uses your ChatGPT account: summaries, answers, takeaways.` (80) |
| Short description, fallback without the trademark | 80 | `Podcast player with AI summaries, answers and one-tap takeaways from episodes.` (78) |

Use the fallback if review objects to "ChatGPT" in the short description.

### Full description

Limit 4,000 characters. YouTube sources and the GitHub update check are left out on purpose (rows 1 and 2).

```
DistillPod is a podcast player with AI that runs on your own ChatGPT account. Sign in with ChatGPT inside the app and the AI features use the plan you already have. There is no AI subscription to buy from DistillPod and no API key to create.

An example: you are listening and hear an idea worth keeping. Tap Distill. DistillPod saves the last 60 seconds and keeps playing. ChatGPT then reads the transcript around that moment and writes a short takeaway, with a quote when the passage has one. Later you find it in Saved, next to the passage it came from, and one tap replays the audio.

WHAT YOUR CHATGPT ACCOUNT ADDS
• Takeaways from one tap, from the player, a headphone button, Android Auto or the home-screen widget.
• A summary of the whole episode with chapters you can jump to.
• Ask the episode: type a question and get an answer with source timestamps you can replay.
• Explain a saved idea: an explanation, a practical reflection and questions to look into.
• Share cards: turn a saved moment into an image and a caption. ChatGPT writes the words; the card is drawn on your phone.
• Podcast suggestions based on the interests you describe.

HOW IT WORKS
1. Follow a show: search the podcast directory, paste an RSS feed or import an OPML file.
2. Connect ChatGPT in Settings → ChatGPT → Continue with ChatGPT.
3. Listen, and tap Distill when something is worth keeping.

TRANSCRIPTS ARE MADE ON YOUR PHONE
DistillPod uses the publisher's transcript when a show provides one. Otherwise it transcribes the episode on your phone with Whisper, after a one-time model download. Episode audio is not uploaded for transcription.

A COMPLETE PLAYER WITHOUT CHATGPT
• Follow public RSS feeds, download episodes, keep a queue.
• Playback speed, skip silence, sleep timer, background playback.
• Android Auto and a home-screen widget.
• Save moments without AI: they keep their passage and audio position.
• Back up your library to a file you choose, and restore it on another phone.

GOOD TO KNOW
• AI requests use your ChatGPT plan's allowance, and plan limits apply. OpenAI decides which accounts and models are eligible. DistillPod has been tested with the developer's own account; other plans are not verified.
• Spoken questions are a separate preview feature that needs your own OpenAI API key.
• AI can be wrong. Every takeaway, summary and answer links back to the source audio so you can check it.
• A takeaway needs the transcript for that part of the episode. The summary and episode chat need the complete transcript, which takes time on the phone.
• The interface is in English.

YOUR DATA
• No DistillPod account and no DistillPod server. Your library, transcripts and results stay on your phone.
• AI features send the transcript text they need, and your question, to OpenAI under your own account.
• Library sync and diagnostics are optional and off until you turn them on.
• No ads.

OPEN SOURCE
Code under GPL-3.0: https://github.com/andrepaim/distillpod

DistillPod is an independent project. It is not affiliated with, sponsored by or endorsed by OpenAI. ChatGPT is a trademark of OpenAI.
```

### Portuguese (Brazil), optional second listing

Add under **Main store listing → Add translation → Portuguese (Brazil)**. The interface and the images are in English, which the text says.

| Field | Value |
| --- | --- |
| App name | `DistillPod: Podcasts com IA` (27) |
| Short description | `Player de podcasts com IA que usa a sua conta do ChatGPT: resumos e respostas.` (78) |

```
O DistillPod é um player de podcasts com IA que funciona com a sua própria conta do ChatGPT. Entre com o ChatGPT dentro do app e os recursos de IA usam o plano que você já tem. Não há assinatura de IA para comprar do DistillPod nem chave de API para criar.

Um exemplo: você está ouvindo e escuta uma ideia que vale guardar. Toque em Distill. O DistillPod salva os últimos 60 segundos e continua tocando. O ChatGPT lê a transcrição daquele trecho e escreve um resumo curto da ideia, com uma citação quando o trecho tem uma. Depois ela está em Saved, ao lado do trecho de origem, e um toque repete o áudio.

O QUE A SUA CONTA DO CHATGPT ACRESCENTA
• Ideias salvas com um toque, pelo player, por um botão do fone, pelo Android Auto ou pelo widget.
• Resumo do episódio inteiro, com capítulos para pular direto ao ponto.
• Pergunte ao episódio: digite uma pergunta e receba a resposta com os trechos de origem para ouvir de novo.
• Explicação de uma ideia salva, com reflexão prática e perguntas para aprofundar.
• Cards para compartilhar: transforme uma ideia salva em imagem e legenda. O ChatGPT escreve o texto; o card é desenhado no seu celular.
• Sugestões de podcasts a partir dos interesses que você descreve.

COMO FUNCIONA
1. Siga um programa: busque no diretório de podcasts, cole um feed RSS ou importe um arquivo OPML.
2. Conecte o ChatGPT em Settings → ChatGPT → Continue with ChatGPT.
3. Ouça, e toque em Distill quando algo valer a pena.

TRANSCRIÇÃO FEITA NO SEU CELULAR
O DistillPod usa a transcrição do próprio programa quando ela existe. Caso contrário, transcreve o episódio no celular com o Whisper, depois de baixar o modelo uma vez. O áudio do episódio não é enviado para transcrição.

UM PLAYER COMPLETO SEM O CHATGPT
• Feeds RSS públicos, downloads e fila de reprodução.
• Velocidade, pular silêncio, timer de sono e reprodução em segundo plano.
• Android Auto e widget na tela inicial.
• Salvar trechos sem IA: eles guardam o trecho e a posição no áudio.
• Backup da biblioteca em um arquivo que você escolhe, com restauração em outro celular.

BOM SABER
• Os pedidos de IA usam a cota do seu plano do ChatGPT, com os limites do plano. A OpenAI define quais contas e modelos são aceitos. O DistillPod foi testado com a conta do próprio desenvolvedor; outros planos não foram verificados.
• Perguntas por voz são um recurso separado, em prévia, que precisa da sua própria chave de API da OpenAI.
• A IA pode errar. Cada ideia, resumo e resposta aponta para o áudio de origem, para você conferir.
• A interface está em inglês.

SEUS DADOS
• Sem conta do DistillPod e sem servidor do DistillPod. Biblioteca, transcrições e resultados ficam no seu celular.
• Os recursos de IA enviam à OpenAI, na sua própria conta, o texto da transcrição de que precisam e a sua pergunta.
• Sincronização da biblioteca e diagnósticos são opcionais e começam desligados.
• Sem anúncios.

CÓDIGO ABERTO
Código sob GPL-3.0: https://github.com/andrepaim/distillpod

O DistillPod é um projeto independente. Não é afiliado, patrocinado nem endossado pela OpenAI. ChatGPT é uma marca da OpenAI.
```

### Graphics

All images are real captures of the app on a Galaxy S22 (public podcast content, no account details), composed by `compose.py`. Screenshot captions avoid the words Play forbids there ("best", "#1", "free", "new") and stay under a fifth of the image.

| Slot | File | Format |
| --- | --- | --- |
| App icon | `icon-512.png` | 512 × 512, 32-bit PNG |
| Feature graphic | `feature-graphic-1024x500.png` | 1024 × 500, 24-bit PNG |
| Phone screenshot 1 | `phone-1-player.png` | 1080 × 1920, 24-bit PNG |
| Phone screenshot 2 | `phone-2-takeaway.png` | same |
| Phone screenshot 3 | `phone-3-summary.png` | same |
| Phone screenshot 4 | `phone-4-ask.png` | same |
| Phone screenshot 5 | `phone-5-share.png` | same |
| Phone screenshot 6 | `phone-6-transcript.png` | same |
| Phone screenshot 7 | `phone-7-connect.png` | same |
| Tablet screenshots | none | Optional; the app has no tablet layout |
| Video | `[YOUTUBE LINK]` | Optional. Upload the demo video to YouTube as unlisted, with ads off, and paste the link |

The images are not committed to the repository. They are kept in `~/.local/share/distillpod-tools/play-store/out/`, next to the captures (`raw/`) and fonts (`assets/`) that `compose.py` reads; `compose.py` and `shots.json` here are copies of the ones in that folder. Rebuild there with `uv run --with pillow python compose.py`.

## Store settings

| Field | Value |
| --- | --- |
| App category | Music & Audio |
| Tags | Podcasts (choose from the console's list) |
| Email | `andrepaim@gmail.com` |
| Website | `https://andrepaim.github.io/distillpod/` once the site is published, or the repository URL |
| Phone | Leave empty |

## App content

### Privacy policy

`https://andrepaim.github.io/distillpod/privacy.html` (published from `site/privacy.html` by the Site workflow once GitHub Pages is enabled for the repository). It must be a public web page, not a PDF.

### Ads

No, the app contains no ads.

### App access

Choose **All or some functionality is restricted** and add one instruction set:

```
Name: AI features (optional)
Username / password: [TEST CHATGPT ACCOUNT, OR LEAVE EMPTY]
Any other information:
DistillPod needs no account for its core use. Searching and following podcasts, playback, downloads, on-device transcription and saving moments all work without signing in.

The AI features (takeaways, summary and chapters, episode chat, share cards, suggestions) use the user's own ChatGPT account. To try them: Settings → ChatGPT → Continue with ChatGPT, sign in on OpenAI's page in the browser and return to the app. DistillPod never sees the password.

To reach an AI result quickly: download the Tiny model in Settings → Transcript preparation, then Search → follow any show → open an episode → Prepare transcript → Summary & chapters.

Every AI result has a Report this result action (in its menu or under the result). It shows the exact message and opens the reviewer's email app; the app itself sends nothing.

The spoken-question preview additionally needs an OpenAI API key entered in Settings → Voice. Library sync (Settings → Sync) uses the reviewer's own Google account.
```

### Content rating

Category: **All other app types**. Answers: no violence, sexual content, profanity, drugs or gambling in the app itself; users cannot communicate with each other or share content with other users inside the app; the app does not share location; no purchases. If asked whether the app gives access to content it does not control, answer yes: podcasts come from their publishers' feeds.

### Target audience

**18 and over** only. The app plays third-party podcasts of any kind and is not designed for children.

### News app

No.

### Data safety

Play counts as "collected" any data the app or its libraries send off the phone. The answers below take the cautious reading for text sent to OpenAI under the user's own account.

| Question | Answer |
| --- | --- |
| Does the app collect or share any of the required user data types? | Yes |
| Is all collected data encrypted in transit? | Yes (OpenAI, Firebase, Sentry and the podcast directory are HTTPS) |
| Account creation | Yes, through OAuth (Google sign-in for optional library sync). If sync is left out of the Play build: no account creation |
| Can users request deletion? | Yes: `https://andrepaim.github.io/distillpod/delete-account.html` |

| Data type | Collected | Shared | Optional | Purpose | Why |
| --- | --- | --- | --- | --- | --- |
| Personal info → Name | Yes | No | Yes | Account management | Google sign-in for library sync |
| Personal info → Email address | Yes | No | Yes | Account management | Same |
| Personal info → User IDs | Yes | No | Yes | Account management, App functionality | Sync account identifier |
| App activity → App interactions | Yes | No | Yes | App functionality, Analytics | Synced listening progress and queue; optional usage statistics |
| App activity → In-app search history | Yes | No | No | App functionality | The podcast directory search text goes to Apple's directory. Processed ephemerally |
| App activity → Other user-generated content | Yes | No | Yes | App functionality | Saved moments in sync; questions and transcript excerpts sent to OpenAI for AI features |
| Audio → Voice or sound recordings | Yes | No | Yes | App functionality | Spoken question in the voice preview. Processed ephemerally |
| App info and performance → Crash logs | Yes | No | Yes | Analytics | Optional diagnostics (Sentry), off by default |
| App info and performance → Diagnostics | Yes | No | Yes | Analytics | Same |
| Device or other IDs | Yes | No | Yes | Analytics | Random installation identifier in optional diagnostics |

Not collected: location, contacts, photos, files, messages, calendar, financial or health data, web history, advertising ID. Text sent to OpenAI is not marked "shared" because it is sent at the user's request to the user's own account.

### Other declarations

| Declaration | Answer |
| --- | --- |
| Advertising ID | No. Confirm the Play build's merged manifest has no `com.google.android.gms.permission.AD_ID` |
| Government app | No |
| Financial features | None |
| Health | No health features |
| COVID-19 contact tracing and status | No |

### Foreground services

One entry per type, each with a link to a short screen recording of the user starting the feature.

| Type | Task | Description to paste |
| --- | --- | --- |
| `mediaPlayback` | Media playback | `The user starts a podcast episode. Playback continues with the screen off or another app in front, with a media notification and lock-screen controls. Stopping it would stop the audio the user is listening to.` |
| `dataSync` | Network transfer the user started | `The user downloads an episode for offline listening. The download continues with a progress notification when the app is in the background. If interrupted, the episode is not available offline.` |
| `mediaProcessing` | Media processing | `The user asks for a transcript of an episode. The app decodes the downloaded audio and transcribes it on the device, which takes several minutes, with a progress notification the user can pause. If deferred, the transcript and the features that depend on it are not ready when the user returns.` |
| `microphone` | Voice input | `The user taps Ask about this in the player, or a headphone button they configured, and speaks a question about the podcast. The microphone is active only while the question is recorded, with a visible indicator. Without it, a question started from a headset with the screen locked cannot be recorded.` |

## After each upload

Every upload needs a higher version code than any earlier one, including GitHub alphas. Re-read this file against the build before submitting an update: the description, the data safety answers and the privacy policy must describe what that build does.

## Rules checked

Checked on 2026-11-27. Pages without a revision date are marked undated.

- Account, fee and identity: <https://support.google.com/googleplay/android-developer/answer/6112435>, <https://support.google.com/googleplay/android-developer/answer/13628312> (undated). Device verification: <https://support.google.com/googleplay/android-developer/answer/14316361>.
- Closed test before production, 12 testers for 14 days: <https://support.google.com/googleplay/android-developer/answer/14151465> (undated).
- App bundles and signing: <https://developer.android.com/guide/app-bundle>, <https://support.google.com/googleplay/android-developer/answer/9842756>.
- Target API level 36: <https://support.google.com/googleplay/android-developer/answer/11926878>.
- Listing text and graphics: <https://support.google.com/googleplay/android-developer/answer/9859152>, <https://support.google.com/googleplay/android-developer/answer/9866151>, <https://support.google.com/googleplay/android-developer/answer/9898842>.
- App content, app access, data safety, account deletion: <https://support.google.com/googleplay/android-developer/answer/9859455>, <https://support.google.com/googleplay/android-developer/answer/15748846>, <https://support.google.com/googleplay/android-developer/answer/10787469>, <https://support.google.com/googleplay/android-developer/answer/13327111>.
- Foreground service declarations: <https://support.google.com/googleplay/android-developer/answer/13392821>.
- Device and Network Abuse (third-party terms, self-update): <https://support.google.com/googleplay/android-developer/answer/9888379>. Intellectual property: <https://support.google.com/googleplay/android-developer/answer/9888072>. Impersonation: <https://support.google.com/googleplay/android-developer/answer/9888374>.
- AI-Generated Content: <https://support.google.com/googleplay/android-developer/answer/14094294>.
- OpenAI brand use and sign-in: <https://openai.com/brand/>, <https://developers.openai.com/siwc/token-sharing-open-source>, <https://developers.openai.com/siwc/ui-ux-guidelines>.

Not verified: whether Play will take over updates of copies installed from GitHub when the same signing key is uploaded; whether the owner's own account counts towards the 12 testers; how long identity verification takes; whether Google sign-in alone counts as account creation for the deletion rule (assumed yes).
