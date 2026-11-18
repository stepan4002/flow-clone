import React from "react";
import {
  AbsoluteFill,
  Easing,
  Img,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { C, FONT } from "./theme";

/** 0 → 1 spring starting at `delay` frames (smooth, no bounce unless asked). */
export const useSpring = (delay = 0, damping = 200, durationInFrames?: number) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return spring({ frame: frame - delay, fps, config: { damping }, durationInFrames });
};

/** Fade + rise entrance. */
export const useEnter = (delay = 0, distance = 40) => {
  const p = useSpring(delay);
  return { opacity: p, transform: `translateY(${(1 - p) * distance}px)` };
};

/** Clamped interpolate shorthand. */
export const lerp = (frame: number, input: number[], output: number[], ease = Easing.inOut(Easing.cubic)) =>
  interpolate(frame, input, output, { extrapolateLeft: "clamp", extrapolateRight: "clamp", easing: ease });

/** Deterministic pseudo-noise in [0, 1]. */
export const noise = (i: number, t: number) => {
  const v = Math.sin(i * 12.9898 + t * 0.21) * 0.5 + Math.sin(i * 4.1414 + t * 0.47) * 0.3 + Math.sin(t * 0.13 + i) * 0.2;
  return v * 0.5 + 0.5;
};

/** Dark backdrop with slowly drifting accent glows and a faint grid. */
export const Background: React.FC<{ hue?: "blue" | "violet" | "mixed" }> = ({ hue = "mixed" }) => {
  const frame = useCurrentFrame();
  const a = frame / 90;
  const g1 = hue === "violet" ? C.violet : C.accent;
  const g2 = hue === "blue" ? C.accent : C.violet;
  return (
    <AbsoluteFill style={{ backgroundColor: C.bg, overflow: "hidden" }}>
      <div
        style={{
          position: "absolute", width: 1300, height: 1300, borderRadius: "50%",
          left: 200 + Math.sin(a) * 160, top: -500 + Math.cos(a * 0.8) * 120,
          background: `radial-gradient(circle, ${g1}55 0%, ${g1}00 65%)`,
        }}
      />
      <div
        style={{
          position: "absolute", width: 1100, height: 1100, borderRadius: "50%",
          right: -250 + Math.cos(a * 0.7) * 140, bottom: -520 + Math.sin(a * 0.9) * 100,
          background: `radial-gradient(circle, ${g2}44 0%, ${g2}00 65%)`,
        }}
      />
      <AbsoluteFill
        style={{
          backgroundImage:
            "linear-gradient(rgba(255,255,255,0.035) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.035) 1px, transparent 1px)",
          backgroundSize: "64px 64px",
          maskImage: "radial-gradient(ellipse at center, black 30%, transparent 75%)",
          WebkitMaskImage: "radial-gradient(ellipse at center, black 30%, transparent 75%)",
        }}
      />
    </AbsoluteFill>
  );
};

/** Scene headline with an optional small label above it. */
export const Headline: React.FC<{ kicker?: string; title: React.ReactNode; delay?: number; top?: number }> = ({
  kicker, title, delay = 0, top = 90,
}) => {
  const k = useEnter(delay, 20);
  const t = useEnter(delay + 6, 30);
  return (
    <div style={{ position: "absolute", top, left: 0, right: 0, textAlign: "center", fontFamily: FONT }}>
      {kicker && (
        <div style={{ ...k, color: C.accentLight, fontSize: 26, fontWeight: 600, letterSpacing: 6, textTransform: "uppercase" }}>
          {kicker}
        </div>
      )}
      <div style={{ ...t, color: C.text, fontSize: 68, fontWeight: 700, marginTop: 14, letterSpacing: -1 }}>{title}</div>
    </div>
  );
};

export const Logo: React.FC<{ size: number }> = ({ size }) => (
  <Img src={staticFile("img/logo.png")} style={{ width: size, height: size, filter: "drop-shadow(0 20px 60px rgba(76,111,255,0.55))" }} />
);

/** Live microphone waveform (bars). `level` 0..1 scales the loudness. */
export const Waveform: React.FC<{ bars?: number; height?: number; color?: string; level?: number; gap?: number; barWidth?: number }> = ({
  bars = 28, height = 60, color = C.accentLight, level = 1, gap = 5, barWidth = 6,
}) => {
  const frame = useCurrentFrame();
  return (
    <div style={{ display: "flex", alignItems: "center", gap, height }}>
      {Array.from({ length: bars }).map((_, i) => {
        const center = 1 - Math.abs(i - bars / 2) / (bars / 2);
        const v = 0.15 + 0.85 * noise(i, frame * 1.6) * (0.35 + 0.65 * center);
        const h = Math.max(4, v * height * level);
        return <div key={i} style={{ width: barWidth, height: h, borderRadius: barWidth, background: color }} />;
      })}
    </div>
  );
};

/** A keyboard key, pressed while `pressed` is true. */
export const Keycap: React.FC<{ label: string; pressed: number }> = ({ label, pressed }) => (
  <div
    style={{
      fontFamily: FONT, fontSize: 40, fontWeight: 700, color: C.text,
      padding: "18px 34px", borderRadius: 18, minWidth: 120, textAlign: "center",
      background: `linear-gradient(180deg, ${C.surface2}, ${C.surface})`,
      border: `2px solid ${pressed > 0.5 ? C.accent : "rgba(255,255,255,0.12)"}`,
      boxShadow: pressed > 0.5 ? `0 0 40px ${C.accent}88, 0 2px 0 #000` : "0 8px 0 #000, 0 12px 30px rgba(0,0,0,0.5)",
      transform: `translateY(${pressed * 6}px)`,
    }}
  >
    {label}
  </div>
);

/** Rounded glass panel. */
export const Panel: React.FC<{ style?: React.CSSProperties; children?: React.ReactNode }> = ({ style, children }) => (
  <div
    style={{
      background: `linear-gradient(180deg, ${C.surface2}F2, ${C.surface}F2)`,
      border: `1px solid ${C.border}`, borderRadius: 28,
      boxShadow: "0 40px 120px rgba(0,0,0,0.55)", fontFamily: FONT, ...style,
    }}
  >
    {children}
  </div>
);

/** Small coloured pill label. */
export const Chip: React.FC<{ children: React.ReactNode; color?: string; style?: React.CSSProperties }> = ({
  children, color = C.accent, style,
}) => (
  <span
    style={{
      display: "inline-flex", alignItems: "center", gap: 10, padding: "8px 18px", borderRadius: 999,
      background: `${color}26`, border: `1px solid ${color}88`, color: C.text, fontFamily: FONT,
      fontSize: 24, fontWeight: 600, ...style,
    }}
  >
    {children}
  </span>
);

/** Typewriter: the first `progress` share of `text` (string slicing, per the Remotion rules). */
export const typed = (text: string, progress: number) => text.slice(0, Math.round(text.length * Math.max(0, Math.min(1, progress))));
