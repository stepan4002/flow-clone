import React from "react";
import { AbsoluteFill, Easing, useCurrentFrame } from "remotion";
import { Background, Keycap, Panel, Waveform, lerp, typed, useEnter, useSpring } from "../components";
import { C, FONT } from "../theme";

// Timeline (frames, 30 fps)
const PRESS = 12;      // keys go down
const SPEAK = 22;      // pill + waveform, words appear as spoken
const RELEASE = 128;   // keys up, transcript cleaned
const CLEAN = 140;     // fillers struck out
const TYPE = 170;      // clean text types into the field
const SEND = 228;      // message is sent

const SPOKEN = [
  { w: "um", filler: true }, { w: "so", filler: true }, { w: "I", filler: false }, { w: "think", filler: false },
  { w: "we", filler: false }, { w: "should", filler: false }, { w: "uh", filler: true }, { w: "meet", filler: false },
  { w: "tomorrow", filler: false }, { w: "at", filler: false }, { w: "ten", filler: false },
  { w: "comma", filler: false, cmd: true }, { w: "works", filler: false }, { w: "for", filler: false },
  { w: "everyone", filler: false }, { w: "question mark", filler: false, cmd: true },
] as const;
const CLEAN_TEXT = "I think we should meet tomorrow at ten, works for everyone?";

const Message: React.FC<{ who: string; text: string; me?: boolean; style?: React.CSSProperties }> = ({ who, text, me, style }) => (
  <div style={{ display: "flex", flexDirection: "column", alignItems: me ? "flex-end" : "flex-start", ...style }}>
    <div style={{ fontSize: 20, color: C.muted, marginBottom: 6 }}>{who}</div>
    <div
      style={{
        maxWidth: 760, padding: "16px 22px", borderRadius: 22, fontSize: 30, lineHeight: 1.35, color: C.text,
        background: me ? `linear-gradient(135deg, ${C.accent}, #3A57E0)` : C.surface2,
        borderBottomRightRadius: me ? 6 : 22, borderBottomLeftRadius: me ? 22 : 6,
      }}
    >
      {text}
    </div>
  </div>
);

export const Dictation: React.FC = () => {
  const frame = useCurrentFrame();
  const win = useSpring(0);
  const pressed = frame >= PRESS && frame < RELEASE ? 1 : 0;
  const pill = useSpring(SPEAK - 6);
  const pillOut = lerp(frame, [RELEASE + 20, RELEASE + 34], [1, 0]);
  const spokenCount = Math.floor(lerp(frame, [SPEAK + 6, RELEASE - 6], [0, SPOKEN.length], Easing.linear));
  const strike = lerp(frame, [CLEAN, CLEAN + 18], [0, 1]);
  const typeP = lerp(frame, [TYPE, TYPE + 45], [0, 1], Easing.linear);
  const sent = useSpring(SEND, 16);
  const caption = useEnter(0, 20);
  const listening = frame >= PRESS && frame < RELEASE; // red + waveform from the key press on

  return (
    <AbsoluteFill>
      <Background hue="blue" />
      {/* caption */}
      <div style={{ ...caption, position: "absolute", top: 70, width: "100%", textAlign: "center", fontFamily: FONT }}>
        <span style={{ fontSize: 64, fontWeight: 700, color: C.text }}>
          Hold <span style={{ color: C.accentLight }}>Ctrl + Win</span>. Speak. Release.
        </span>
      </div>

      {/* chat window */}
      <Panel
        style={{
          position: "absolute", left: 260, top: 200, width: 1000, height: 700, overflow: "hidden",
          opacity: win, transform: `translateY(${(1 - win) * 60}px)`,
        }}
      >
        <div style={{ height: 76, borderBottom: `1px solid ${C.border}`, display: "flex", alignItems: "center", padding: "0 30px", gap: 16 }}>
          <div style={{ width: 16, height: 16, borderRadius: 8, background: C.green }} />
          <span style={{ fontSize: 28, fontWeight: 700, color: C.text }}># team-launch</span>
        </div>
        <div style={{ padding: 34, display: "flex", flexDirection: "column", gap: 26 }}>
          <Message who="Anna" text="When can we review the new build?" />
          {frame >= SEND && (
            <Message
              who="You" me text={CLEAN_TEXT}
              style={{ opacity: sent, transform: `translateY(${(1 - sent) * 30}px) scale(${0.9 + sent * 0.1})` }}
            />
          )}
        </div>
        {/* input field */}
        <div
          style={{
            position: "absolute", left: 30, right: 30, bottom: 30, minHeight: 84, borderRadius: 20,
            border: `2px solid ${frame >= TYPE && frame < SEND ? C.accent : C.border}`, background: C.bg,
            padding: "22px 26px", fontSize: 30, color: C.text, boxSizing: "border-box",
          }}
        >
          {frame < SEND && frame >= TYPE ? (
            <>
              {typed(CLEAN_TEXT, typeP)}
              <span style={{ opacity: Math.floor(frame / 8) % 2 ? 1 : 0, color: C.accentLight }}>▍</span>
            </>
          ) : (
            <span style={{ color: C.muted }}>Message #team-launch</span>
          )}
        </div>
      </Panel>

      {/* right side: keys + transcript */}
      <div style={{ position: "absolute", left: 1330, top: 230, width: 520, fontFamily: FONT }}>
        <div style={{ display: "flex", gap: 22, alignItems: "center", opacity: win }}>
          <Keycap label="Ctrl" pressed={pressed} />
          <span style={{ fontSize: 40, color: C.muted }}>+</span>
          <Keycap label="Win" pressed={pressed} />
        </div>
        <div style={{ marginTop: 50, fontSize: 24, color: C.muted, letterSpacing: 4, opacity: pill }}>
          {frame < RELEASE ? "YOU SAY" : frame < TYPE ? "CLEANING UP" : "FLOWCLONE TYPES"}
        </div>
        <div style={{ marginTop: 14, fontSize: 34, lineHeight: 1.5, color: C.text, minHeight: 260, opacity: pill }}>
          {SPOKEN.slice(0, spokenCount).map((s, i) => {
            const isCmd = "cmd" in s && s.cmd;
            return (
              <span
                key={i}
                style={{
                  marginRight: 12, display: "inline-block",
                  color: s.filler ? (strike > 0 ? C.red : C.text) : isCmd ? C.accentLight : C.text,
                  textDecoration: s.filler && strike > 0.5 ? "line-through" : "none",
                  opacity: s.filler ? 1 - strike * 0.6 : 1,
                  fontStyle: isCmd ? "italic" : "normal",
                }}
              >
                {s.w}
              </span>
            );
          })}
        </div>
        {frame >= CLEAN && (
          <div style={{ display: "flex", gap: 12, flexWrap: "wrap", opacity: strike }}>
            {["fillers removed", "“comma” → ,", "capitalized"].map((t) => (
              <span key={t} style={{ fontSize: 22, padding: "8px 16px", borderRadius: 999, background: `${C.green}26`, border: `1px solid ${C.green}88`, color: C.text }}>
                ✓ {t}
              </span>
            ))}
          </div>
        )}
      </div>

      {/* the FlowClone status pill */}
      <div
        style={{
          position: "absolute", left: 760 - 170, top: 940, width: 340, height: 74, borderRadius: 37,
          background: "rgba(20,20,26,0.92)", border: `1px solid ${listening ? C.red : C.border}`,
          display: "flex", alignItems: "center", justifyContent: "center", gap: 16,
          opacity: pill * pillOut, transform: `translateY(${(1 - pill) * 40}px) scale(${0.8 + pill * 0.2})`,
          boxShadow: listening ? `0 0 50px ${C.red}55` : "0 20px 60px rgba(0,0,0,0.6)", fontFamily: FONT,
        }}
      >
        <div style={{ width: 16, height: 16, borderRadius: 8, background: listening ? C.red : C.accent }} />
        {listening ? (
          <Waveform bars={22} height={40} color={C.text} barWidth={5} gap={4} />
        ) : (
          <span style={{ fontSize: 24, color: C.text }}>Transcribing…</span>
        )}
      </div>
    </AbsoluteFill>
  );
};
