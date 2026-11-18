import React from "react";
import { AbsoluteFill, Easing, useCurrentFrame } from "remotion";
import { Background, Headline, lerp, useEnter } from "../components";
import { C, FONT } from "../theme";

const Row: React.FC<{ label: string; icon: string; wpm: number; max: number; color: string; delay: number }> = ({
  label, icon, wpm, max, color, delay,
}) => {
  const frame = useCurrentFrame();
  const enter = useEnter(delay, 30);
  const p = lerp(frame, [delay + 8, delay + 50], [0, 1], Easing.out(Easing.cubic));
  return (
    <div style={{ ...enter, display: "flex", alignItems: "center", gap: 36, width: 1400, fontFamily: FONT }}>
      <div style={{ width: 300, color: C.text, fontSize: 44, fontWeight: 600 }}>
        <span style={{ marginRight: 16 }}>{icon}</span>
        {label}
      </div>
      <div style={{ flex: 1, height: 64, borderRadius: 32, background: "rgba(255,255,255,0.06)", overflow: "hidden" }}>
        <div
          style={{
            width: `${(wpm / max) * p * 100}%`, height: "100%", borderRadius: 32,
            background: `linear-gradient(90deg, ${color}AA, ${color})`, boxShadow: `0 0 40px ${color}66`,
          }}
        />
      </div>
      <div style={{ width: 260, textAlign: "right", color: C.text, fontSize: 64, fontWeight: 800 }}>
        {Math.round(wpm * p)}
        <span style={{ fontSize: 30, color: C.muted, fontWeight: 600 }}> wpm</span>
      </div>
    </div>
  );
};

export const Speed: React.FC = () => {
  const foot = useEnter(70, 20);
  return (
    <AbsoluteFill>
      <Background hue="violet" />
      <Headline kicker="Why voice" title="You speak almost 4× faster than you type" />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", gap: 70, top: 120 }}>
        <Row label="Typing" icon="⌨️" wpm={40} max={160} color={C.muted} delay={12} />
        <Row label="Speaking" icon="🎙️" wpm={150} max={160} color={C.accent} delay={24} />
        <div style={{ ...foot, fontFamily: FONT, fontSize: 34, color: C.muted, marginTop: 10 }}>
          FlowClone turns what you say into clean, ready-to-send text — in any app.
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
