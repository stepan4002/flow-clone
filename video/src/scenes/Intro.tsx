import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { Background, Logo, Waveform, lerp, useEnter, useSpring } from "../components";
import { C, FONT } from "../theme";

export const Intro: React.FC = () => {
  const frame = useCurrentFrame();
  const logo = useSpring(0, 12);
  const title = useEnter(14, 40);
  const tag = useEnter(26, 30);
  const plats = useEnter(40, 20);
  const ring = (frame % 45) / 45;
  return (
    <AbsoluteFill>
      <Background hue="blue" />
      <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", fontFamily: FONT }}>
        <div style={{ position: "relative", width: 220, height: 220, marginTop: -120 }}>
          {[0, 0.5].map((o) => {
            const r = (ring + o) % 1;
            return (
              <div
                key={o}
                style={{
                  position: "absolute", inset: -40 - r * 140, borderRadius: "50%",
                  border: `3px solid ${C.accent}`, opacity: (1 - r) * 0.45 * logo,
                }}
              />
            );
          })}
          <div style={{ transform: `scale(${0.4 + logo * 0.6}) rotate(${(1 - logo) * -12}deg)`, opacity: logo }}>
            <Logo size={220} />
          </div>
        </div>
        <div style={{ ...title, fontSize: 132, fontWeight: 800, color: C.text, letterSpacing: -4, marginTop: 50 }}>
          Flow<span style={{ color: C.accentLight }}>Clone</span>
        </div>
        <div style={{ ...tag, fontSize: 46, color: C.muted, marginTop: 8 }}>Speak instead of type.</div>
        <div style={{ ...plats, display: "flex", gap: 22, marginTop: 44, alignItems: "center" }}>
          <Waveform bars={14} height={34} level={lerp(frame, [40, 70], [0.2, 1])} barWidth={5} gap={4} />
          <span style={{ fontSize: 28, color: C.text, fontWeight: 600, letterSpacing: 2 }}>WINDOWS · ANDROID · FREE</span>
          <Waveform bars={14} height={34} level={lerp(frame, [40, 70], [0.2, 1])} barWidth={5} gap={4} />
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
