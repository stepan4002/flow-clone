import React from "react";
import { AbsoluteFill, Sequence, staticFile } from "remotion";
import { Audio } from "@remotion/media";
import { TransitionSeries, linearTiming } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { slide } from "@remotion/transitions/slide";
import { Intro } from "./scenes/Intro";
import { Speed } from "./scenes/Speed";
import { Dictation } from "./scenes/Dictation";
import { Styles } from "./scenes/Styles";
import { VoiceEdit } from "./scenes/VoiceEdit";
import { AndroidApp, WindowsApp } from "./scenes/Apps";
import { Outro, PRIVACY_STEPS, Privacy } from "./scenes/Outro";

const T = 18; // transition length (frames)

type Sfx = { at: number; file: "pop" | "tick" | "chime" | "whoosh"; volume?: number };

/** Scenes in order, with their sound effects (frames relative to the scene start). */
const SCENES: { id: string; Comp: React.FC; dur: number; sfx: Sfx[] }[] = [
  { id: "intro", Comp: Intro, dur: 135, sfx: [{ at: 2, file: "pop" }, { at: 16, file: "chime", volume: 0.6 }] },
  { id: "speed", Comp: Speed, dur: 135, sfx: [{ at: 20, file: "pop" }, { at: 32, file: "pop" }] },
  {
    id: "dictation", Comp: Dictation, dur: 270,
    sfx: [
      { at: 12, file: "tick", volume: 1 }, { at: 22, file: "pop" }, { at: 128, file: "tick", volume: 1 },
      { at: 140, file: "pop", volume: 0.6 },
      ...Array.from({ length: 15 }, (_, i) => ({ at: 170 + i * 3, file: "tick" as const, volume: 0.5 })),
      { at: 228, file: "pop" },
    ],
  },
  { id: "styles", Comp: Styles, dur: 210, sfx: [{ at: 40, file: "pop" }, { at: 56, file: "pop" }, { at: 72, file: "pop" }] },
  { id: "voice-edit", Comp: VoiceEdit, dur: 200, sfx: [{ at: 30, file: "tick" }, { at: 60, file: "pop" }, { at: 128, file: "chime", volume: 0.7 }] },
  { id: "windows", Comp: WindowsApp, dur: 210, sfx: [{ at: 60, file: "pop" }, { at: 72, file: "pop" }, { at: 84, file: "pop" }] },
  { id: "android", Comp: AndroidApp, dur: 225, sfx: [{ at: 14, file: "pop" }, { at: 24, file: "pop" }, { at: 34, file: "pop" }] },
  { id: "privacy", Comp: Privacy, dur: 165, sfx: PRIVACY_STEPS.map((at) => ({ at, file: "pop" as const, volume: 0.8 })) },
  { id: "outro", Comp: Outro, dur: 180, sfx: [{ at: 0, file: "chime", volume: 0.8 }] },
];

/** Where each scene starts on the final timeline (transitions overlap neighbours). */
const starts = SCENES.reduce<number[]>((acc, s, i) => {
  acc.push(i === 0 ? 0 : acc[i - 1] + SCENES[i - 1].dur - T);
  return acc;
}, []);

export const TOTAL_FRAMES = starts[starts.length - 1] + SCENES[SCENES.length - 1].dur;

const presentationFor = (i: number) =>
  i % 3 === 2 ? slide({ direction: "from-right" }) : fade();

export const FlowCloneVideo: React.FC = () => (
  <AbsoluteFill style={{ backgroundColor: "#000" }}>
    <TransitionSeries>
      {SCENES.flatMap(({ id, Comp, dur }, i) => {
        const items = [
          <TransitionSeries.Sequence key={id} durationInFrames={dur} premountFor={30}>
            <Comp />
          </TransitionSeries.Sequence>,
        ];
        if (i < SCENES.length - 1) {
          items.push(
            <TransitionSeries.Transition key={`${id}-t`} presentation={presentationFor(i)} timing={linearTiming({ durationInFrames: T })} />,
          );
        }
        return items;
      })}
    </TransitionSeries>

    <Audio src={staticFile("audio/music.wav")} volume={0.75} />
    {starts.slice(1).map((s, i) => (
      <Sequence key={`w${i}`} from={s - 4} durationInFrames={24} layout="none">
        <Audio src={staticFile("audio/whoosh.wav")} volume={0.35} />
      </Sequence>
    ))}
    {SCENES.flatMap((scene, i) =>
      scene.sfx.map((fx, j) => (
        <Sequence key={`${scene.id}-${j}`} from={starts[i] + fx.at} durationInFrames={40} layout="none">
          <Audio src={staticFile(`audio/${fx.file}.wav`)} volume={(fx.volume ?? 0.7) * 0.6} />
        </Sequence>
      )),
    )}
  </AbsoluteFill>
);
