import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { Background, Headline, Logo, lerp, useEnter, useSpring } from "../components";
import { C, FONT, MONO } from "../theme";

export const PRIVACY_STEPS = [16, 34, 52, 70];

export const Privacy: React.FC = () => {
  const items = [
    { t: "Speech recognized on your device", s: "Audio never leaves your PC or phone" },
    { t: "No account · no cloud · no limits", s: "Unlimited words, works offline" },
    { t: "AI only if you turn it on", s: "Your own Ollama server, or your own API key" },
    { t: "Free & open source", s: "MIT license — read every line" },
  ];
  return (
    <AbsoluteFill>
      <Background hue="blue" />
      <Headline kicker="Private by design" title="Your voice stays yours" top={80} />
      <div style={{ position: "absolute", top: 330, left: 0, right: 0, display: "flex", flexDirection: "column", alignItems: "center", gap: 30, fontFamily: FONT }}>
        {items.map((it, i) => {
          const p = useSpring(PRIVACY_STEPS[i], 16);
          return (
            <div key={it.t} style={{ display: "flex", alignItems: "center", gap: 30, width: 1100, opacity: p, transform: `translateX(${(1 - p) * -80}px)` }}>
              <div
                style={{
                  width: 72, height: 72, borderRadius: 36, background: `${C.green}2A`, border: `2px solid ${C.green}`,
                  display: "flex", alignItems: "center", justifyContent: "center", fontSize: 38, color: C.green,
                  transform: `scale(${0.5 + p * 0.5})`,
                }}
              >
                ✓
              </div>
              <div>
                <div style={{ fontSize: 46, fontWeight: 700, color: C.text }}>{it.t}</div>
                <div style={{ fontSize: 28, color: C.muted, marginTop: 4 }}>{it.s}</div>
              </div>
            </div>
          );
        })}
      </div>
    </AbsoluteFill>
  );
};

export const Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const logo = useSpring(0, 12);
  const title = useEnter(10, 30);
  const tag = useEnter(20, 20);
  const url = useEnter(32, 20);
  const fadeOut = lerp(frame, [150, 180], [1, 0]);
  return (
    <AbsoluteFill style={{ backgroundColor: "#000" }}>
      <AbsoluteFill style={{ opacity: fadeOut }}>
        <Background hue="mixed" />
        <AbsoluteFill style={{ alignItems: "center", justifyContent: "center", fontFamily: FONT }}>
          <div style={{ transform: `scale(${0.5 + logo * 0.5})`, opacity: logo }}>
            <Logo size={180} />
          </div>
          <div style={{ ...title, fontSize: 120, fontWeight: 800, color: C.text, letterSpacing: -4, marginTop: 30 }}>
            Flow<span style={{ color: C.accentLight }}>Clone</span>
          </div>
          <div style={{ ...tag, fontSize: 44, color: C.muted }}>Speak instead of type. Free, forever.</div>
          <div
            style={{
              ...url, marginTop: 50, padding: "18px 36px", borderRadius: 999, fontFamily: MONO, fontSize: 34, color: C.text,
              background: "rgba(255,255,255,0.06)", border: `1px solid ${C.accent}`, boxShadow: `0 0 50px ${C.accent}44`,
            }}
          >
            github.com/stepan4002/flow-clone
          </div>
          <div style={{ ...url, marginTop: 28, fontSize: 28, color: C.muted, letterSpacing: 4 }}>WINDOWS · ANDROID</div>
        </AbsoluteFill>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
