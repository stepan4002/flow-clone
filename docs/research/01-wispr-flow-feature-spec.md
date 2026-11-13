# Wispr Flow — Feature Inventory & Behavior Spec (research, July 2026)

Compiled from wisprflow.ai (homepage, /features, /android, /pricing, /whats-new), docs.wisprflow.ai,
9to5Google, MakeTechEasier, eesel, Spokenly, WriteInteractive and user reviews.
This is the spec the clone in this folder is built against.

---

## 1. Product overview

Wispr Flow is a **cloud-based** AI dictation utility that works system-wide in any application.
Hold a hotkey, speak naturally (fillers, false starts, self-corrections included), release,
and ~1 second later polished formatted text appears at the cursor of whatever app is focused.
Marketed at ~220 WPM effective writing speed. Platforms: macOS, Windows, iOS (keyboard
extension), Android (floating bubble). Settings/dictionary/snippets sync via account.

Pipeline: local audio capture → cloud ASR (Whisper-family + proprietary) → LLM post-processing
"Polish" layer (subprocessors: Baseten, OpenAI, Anthropic, Cerebras, AWS) → text injection into
the focused field. **No on-device/offline mode at any tier — internet required.**
(→ The clone's biggest win: 100% local, free, private, offline.)

## 2. Activation (Windows)

- **Push-to-talk (primary)**: default hotkey **Ctrl + Win** — hold to record, release to
  stop → transcribe → insert.
- **Hands-free (toggle) mode**: double-tap the push-to-talk shortcut, or dedicated shortcut
  (default `Ctrl+Win+Space`), or click the Flow Bar. Audio ping + animated waveform confirms
  listening. Stop with the shortcut again or the stop icon. **Session cap 20 min desktop**
  (warning at 19), 5 min Android.
- Saying **"press enter"** at the end of a hands-free dictation makes Flow hit Enter after
  pasting (phrase stripped from output).
- **Shortcut system**: every action bindable (push-to-talk, hands-free, Command Mode,
  Transforms, View Diff, Scratchpad, paste last transcript `Shift+Alt+Z`, copy last
  `Shift+Alt+X`, cancel `Esc`). Up to 4 shortcuts per action, max 3 keys. Mouse buttons
  4–10 / middle-click usable ("Mouse Flow").

## 3. Text insertion — the "works in any app" trick

Desktop: **not an IME**. It saves the current clipboard, puts the transcript on the clipboard,
simulates **Ctrl+V** into the focused field, then restores the previous clipboard (~150 ms
later). Works in any app that accepts paste (browsers, Slack, VS Code, terminals, Notion...).
Fallback: if paste fails, transcript stays on clipboard + toast; also retrievable via
"paste last transcript" and History.

Android: direct injection via **Accessibility Service** in verified apps; clipboard fallback
with up to 5 retries.

## 4. AI cleanup ("Auto Cleanup" / Polish layer)

All on by default; toggles in Settings → Auto Cleanup.

- **Filler removal**: um, uh, verbal pauses, false starts.
- **Auto punctuation**: inferred from pauses/intonation; explicit dictation also works
  ("period", "comma", "em dash", "new line", "new paragraph", ...).
- **Capitalization/casing** + automatic leading/trailing spacing relative to cursor context.
- **Backtrack (self-correction)** — flagship:
  - Trigger style: "coffee at 2, **actually** 3" → "coffee at 3". Also "scratch that",
    "no wait".
  - Restatement style: just re-say the phrase; the LLM infers the correction.
- **Smart Formatting**: spoken enumeration → numbered/bulleted lists; drops trailing periods
  in messaging apps for casual tone; number/email/URL formatting; grammar fixing.
- **Name spelling from context** (on-screen context feeds the ASR).

## 5. Tone / context matching

Flow reads which app is focused and adapts:
- Slack/Teams/WhatsApp → casual, no trailing period ("hey Jordan")
- Email (Gmail/Outlook) → professional ("Hello Jordan,")
- Google Docs → formal prose; Notion → block formatting
- Developer context: camelCase/snake_case, CLI commands, jargon; remembers filenames in
  Cursor/Windsurf (auto-@-tags files in AI prompts).
- "Personalized Style": per-app-category tone preferences; learns from your edits
  (desktop + English only).

## 6. Personal dictionary & text replacements

- Dictionary tab: add words/phrases → biases ASR toward them. Starred entries.
- **Auto-learning**: corrections you type over are silently learned.
- Misspelling pairs (always "Erik" not "Eric").
- **Text Replacements**: shorthand → expansion ("c30" → Calendly link).
- Team shared dictionary (Team/Enterprise).

## 7. Snippets (voice-triggered expansion)

Trigger phrase + expansion text; say the trigger during dictation → full formatted block
inserted (signatures, addresses, links). Ctrl+F search, Ctrl+N new, team sharing.

## 8. Voice editing (Pro)

- **Command Mode** (experimental, desktop, default `Ctrl+Win+Alt` hold): with text selected
  (≤1,000 words) a spoken instruction transforms the selection in place ("make this more
  concise", "rewrite as a bulleted list", "translate to Polish"). Without selection: inline
  AI assistant — answer inserted at cursor. Voice-configurable settings with Apply
  confirmation.
- **Transforms (beta)**: select text → hotkey → AI rewrite. Presets: Polish (`Win+Alt+1`),
  Prompt Engineer (`Win+Alt+2`); chips: More concise/professional/casual, Turn to list/table.
- **View Diff** (`Win+Alt+O`): diff viewer, teal additions, strikethrough removals.

## 9. Whispering & languages

- Whispering: no toggle, ASR trained for very quiet speech (~92–95% accuracy whispered).
- **104 languages** incl. regional variants and code-switching (Hinglish). Auto-detect at the
  start of each dictation among the user's selected languages; language picker in Flow Bar.

## 10. Android app (architecturally different)

**Not a keyboard/IME** — a floating **"Flow Bubble" overlay** powered by an Accessibility
Service. Appears above the existing keyboard whenever a text field gains focus.

- Permissions: display over other apps, accessibility service, microphone,
  battery-optimization exemption.
- Interaction: tap into field → bubble appears → **tap = toggle recording; long-press =
  push-to-talk** → checkmark/stop → cleaned text injected.
- Insertion: direct accessibility injection in verified apps (WhatsApp, Telegram, Messenger,
  Instagram, Teams, Signal, Slack, Discord, Snapchat, Google Messages, Gmail); clipboard
  fallback with 5 retries + toast.
- Same AI pipeline as desktop; Smart Formatting cannot be disabled.
- Bubble customization: 4 sizes, 5 opacity levels, shrink when idle / in search fields.
- Privacy: bubble auto-hides in 50+ banking apps and password/PIN fields; Privacy Mode;
  work-profile support.
- Limits: Android 13+, internet required, 5-min session cap.
- Lacks vs Windows: Command Mode, Transforms, Scratchpad, hotkeys, per-app Styles.

> Clone note: without Wispr's cloud, the most practical local equivalent is a **voice
> keyboard (IME)** — Android's sanctioned way to type into any app, used by every
> open-source local dictation app (FUTO, Transcribro, Sayboard). Our clone does that.

## 11. UI elements (Windows)

- **Flow Bar**: floating pill near screen bottom; idle mic icon → animated waveform while
  recording → processing state; language picker; click = hands-free toggle; hideable.
- **Tray icon**: resident app, auto-start with Windows.
- **Flow Hub** (Electron window): Home/Recent activity (full dictation history — view, copy,
  re-paste), Dictionary, Snippets, Transforms, Notes/Scratchpad, Settings (Shortcuts,
  Auto Cleanup, Experimental, Data & Privacy, Account/word meter).
- **Scratchpad**: rich-text notepad with tabs + version history; hold its shortcut to dictate
  straight into it.
- Mic management: rank preferred microphones, auto-switch.

## 12. Pricing (mid-2026) — what free must include vs Pro

| Tier | Price | Includes |
|---|---|---|
| Basic (free) | $0 | Core dictation, **2,000 words/week** desktop (5,000 cap), 1,000/wk iOS, Android unlimited (temporary). Resets Sundays. |
| Pro | $15/mo ($12 annual) | Unlimited words, Command Mode, early access, 100+ languages, priority support. 14-day trial. |
| Team | Pro per-seat | + shared dictionary & snippets, admin. |
| Enterprise | Sales | + SOC 2, HIPAA, ZDR, SSO, dashboards. |

The clone includes everything with no word limits — it's all local.

## 13. Privacy model & known limitations (clone opportunities)

- Cloud-only; default (free) dictations may be stored and used for training. Privacy Mode is
  opt-in Pro / default Enterprise. Publicized privacy incident + idle telemetry complaints.
- Electron Windows app: ~800 MB RAM, ~8% CPU idle; reports of freezing target apps during
  paste; unusable offline; accuracy perceived to drop after trial; 20-min/5-min session caps.

Key sources: wisprflow.ai/features, /android, /pricing, /whats-new; docs.wisprflow.ai articles
on hands-free, hotkeys, Android setup, Command Mode, Transforms, Smart Formatting & Backtrack,
dictionary, snippets, plans, privacy/ZDR, context awareness, languages, Flow Bar/Bubble, paste
troubleshooting; 9to5Google Android hands-on (Feb 2026); MakeTechEasier; eesel; Spokenly;
WriteInteractive reviews.
