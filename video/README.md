# FlowClone presentation video

The 53-second product video, made in code with [Remotion](https://www.remotion.dev/) (React → MP4).
Everything in it is generated in this folder: the scenes in `src/`, the app screenshots in
`public/img/`, and an original soundtrack and sound effects synthesized by `tools/make_audio.py`
(numpy/scipy; no samples, no licences needed).

```bash
cd video
npm install
npm run audio      # python tools/make_audio.py → public/audio/*.wav (needs numpy + scipy)
npm run studio     # live preview / timeline in the browser
npm run render     # → out/flowclone.mp4 (1920×1080, 30 fps, H.264 + AAC)
npm run poster     # → out/poster.png
```

| Scene | File |
|---|---|
| Logo intro | `src/scenes/Intro.tsx` |
| Typing vs speaking speed | `src/scenes/Speed.tsx` |
| Live dictation: Ctrl+Win, pill, cleanup | `src/scenes/Dictation.tsx` |
| Styles per app | `src/scenes/Styles.tsx` |
| Voice edit | `src/scenes/VoiceEdit.tsx` |
| Windows app, Android app | `src/scenes/Apps.tsx` |
| Privacy, outro | `src/scenes/Outro.tsx` |

Scene lengths, transitions and sound-effect cues are in `src/FlowCloneVideo.tsx`; colours and
fonts in `src/theme.ts` (the apps' design tokens). If you change the total length, update
`music(seconds=…)` in `tools/make_audio.py` to match.

Remotion is free for individuals and companies of up to 3 people; larger companies need a
[company licence](https://www.remotion.dev/license).
