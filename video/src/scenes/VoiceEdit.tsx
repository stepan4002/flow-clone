import React from "react";
import { AbsoluteFill, interpolate, useCurrentFrame } from "remotion";
import { Background, Chip, Headline, Panel, Waveform, lerp, typed, useEnter, useSpring } from "../components";
import { C, FONT } from "../theme";

const BEFORE = "the new build is way faster and honestly it just works now";
const AFTER = "The new build is significantly faster and now works reliably.";
const INSTRUCTION = "make it more formal";

const SELECT = 30;  // selection sweeps over the sentence
const ASK = 60;     // bubble with the spoken instruction
const SWAP = 128;   // rewritten text replaces the selection

export const VoiceEdit: React.FC = () => {
  const frame = useCurrentFrame();
  const doc = useEnter(6, 50);
  const sel = lerp(frame, [SELECT, SELECT + 24], [0, 1]);
  const bubble = useSpring(ASK, 14);
  const said = lerp(frame, [ASK + 10, ASK + 50], [0, 1]);
  const swap = lerp(frame, [SWAP, SWAP + 14], [0, 1]);
  const glow = interpolate(frame, [SWAP, SWAP + 10, SWAP + 40], [0, 1, 0], { extrapolateLeft: "clamp", extrapolateRight: "clamp" });
  const foot = useEnter(150, 20);

  return (
    <AbsoluteFill>
      <Background hue="mixed" />
      <Headline kicker="Voice edit" title="Select text. Say what to change." top={70} />
      <Panel style={{ ...doc, position: "absolute", left: 260, top: 300, width: 1400, padding: "50px 64px", boxSizing: "border-box" }}>
        <div style={{ fontSize: 24, color: C.muted, marginBottom: 22 }}>Release notes.docx</div>
        <div style={{ fontSize: 40, lineHeight: 1.6, color: C.text }}>
          <span>Version 1.3 is out. </span>
          <span style={{ position: "relative", display: "inline" }}>
            {swap < 1 && (
              <span
                style={{
                  opacity: 1 - swap, borderRadius: 8, padding: "2px 4px",
                  background: `linear-gradient(90deg, ${C.accent}88 ${sel * 100}%, transparent ${sel * 100}%)`,
                }}
              >
                {BEFORE}
              </span>
            )}
            {swap > 0 && (
              <span style={{ opacity: swap, color: C.text, textShadow: `0 0 ${glow * 30}px ${C.accentLight}`, background: `${C.accent}${Math.round(glow * 80).toString(16).padStart(2, "0")}`, borderRadius: 8 }}>
                {AFTER}
              </span>
            )}
          </span>
          <span> Thanks to everyone who tested it.</span>
        </div>
      </Panel>
      {frame >= ASK && (
        <div
          style={{
            position: "absolute", left: 660, top: 690, opacity: bubble * (1 - swap * 0.4),
            transform: `translateY(${(1 - bubble) * 30}px) scale(${0.85 + bubble * 0.15})`,
            display: "flex", alignItems: "center", gap: 24, padding: "22px 34px", borderRadius: 999,
            background: "rgba(20,20,26,0.95)", border: `1px solid ${C.accent}`, boxShadow: `0 0 60px ${C.accent}44`, fontFamily: FONT,
          }}
        >
          <span style={{ fontSize: 36 }}>✨</span>
          <Waveform bars={12} height={36} level={frame < SWAP ? 1 : 0.25} barWidth={5} gap={4} />
          <span style={{ fontSize: 38, color: C.text, fontStyle: "italic", minWidth: 380 }}>“{typed(INSTRUCTION, said)}”</span>
        </div>
      )}
      <div style={{ ...foot, position: "absolute", bottom: 80, width: "100%", display: "flex", justifyContent: "center", gap: 24 }}>
        <Chip>Windows: Ctrl + Win + Shift</Chip>
        <Chip color={C.violet}>Android: ✨ Edit</Chip>
        <Chip color={C.green}>“translate to German” · “make it shorter”</Chip>
      </div>
    </AbsoluteFill>
  );
};
