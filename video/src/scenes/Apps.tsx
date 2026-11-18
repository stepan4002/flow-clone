import React from "react";
import { AbsoluteFill, Img, staticFile, useCurrentFrame } from "remotion";
import { Background, Chip, Headline, lerp, useEnter, useSpring } from "../components";
import { C, FONT } from "../theme";

/** A screenshot in a window frame, floating in 3D. */
const Shot: React.FC<{ src: string; width: number; delay: number; x: number; y: number; rot: number; z: number }> = ({
  src, width, delay, x, y, rot, z,
}) => {
  const frame = useCurrentFrame();
  const p = useSpring(delay);
  const drift = Math.sin((frame + delay * 3) / 40) * 8;
  return (
    <div
      style={{
        position: "absolute", left: x, top: y + drift + (1 - p) * 120, width, zIndex: z, opacity: p,
        transform: `perspective(2400px) rotateY(${rot * p + (1 - p) * rot * 2}deg)`,
        borderRadius: 18, overflow: "hidden", border: `1px solid rgba(255,255,255,0.12)`,
        boxShadow: "0 50px 140px rgba(0,0,0,0.7)",
      }}
    >
      <div style={{ height: 36, background: "#1B1B21", display: "flex", alignItems: "center", gap: 9, paddingLeft: 16 }}>
        {["#FF5F57", "#FEBC2E", "#28C840"].map((c) => <div key={c} style={{ width: 12, height: 12, borderRadius: 6, background: c, opacity: 0.8 }} />)}
      </div>
      <Img src={staticFile(src)} style={{ width, display: "block" }} />
    </div>
  );
};

export const WindowsApp: React.FC = () => {
  const frame = useCurrentFrame();
  const chips = [
    { t: "🔥 12-day streak", d: 60 },
    { t: "⚡ 144 wpm", d: 72 },
    { t: "⏱ 17 min saved", d: 84 },
  ];
  const zoom = lerp(frame, [0, 210], [1, 1.04]);
  return (
    <AbsoluteFill>
      <Background hue="blue" />
      <Headline kicker="FlowClone for Windows" title="Insights, history, styles — in one place" top={60} />
      <AbsoluteFill style={{ transform: `scale(${zoom})` }}>
        <Shot src="img/windows-styles.png" width={800} delay={22} x={150} y={300} rot={18} z={1} />
        <Shot src="img/windows-history.png" width={800} delay={30} x={970} y={300} rot={-18} z={1} />
        <Shot src="img/windows-home.png" width={940} delay={8} x={490} y={245} rot={0} z={2} />
      </AbsoluteFill>
      <div style={{ position: "absolute", bottom: 40, width: "100%", display: "flex", justifyContent: "center", gap: 22, zIndex: 3 }}>
        {chips.map((c) => {
          const e = useEnter(c.d, 30);
          return <Chip key={c.t} style={{ ...e, fontSize: 30, padding: "12px 26px", background: "rgba(20,20,26,0.92)" }}>{c.t}</Chip>;
        })}
      </div>
    </AbsoluteFill>
  );
};

const Phone: React.FC<{ src: string; delay: number; x: number; rot: number; label: string }> = ({ src, delay, x, rot, label }) => {
  const frame = useCurrentFrame();
  const p = useSpring(delay, 18);
  const drift = Math.sin((frame + delay * 2) / 35) * 10;
  const w = 300;
  return (
    <div style={{ position: "absolute", left: x, top: 190 + (1 - p) * 300 + drift, opacity: p, transform: `rotate(${rot}deg)`, textAlign: "center", fontFamily: FONT }}>
      <div
        style={{
          width: w, height: w * (2400 / 1080), borderRadius: 54, padding: 12, background: "#050507",
          border: "2px solid rgba(255,255,255,0.14)", boxShadow: "0 60px 140px rgba(0,0,0,0.7)", boxSizing: "content-box",
        }}
      >
        <Img src={staticFile(src)} style={{ width: w, height: w * (2400 / 1080), borderRadius: 44, display: "block" }} />
      </div>
      <div style={{ marginTop: 22, fontSize: 28, color: C.text, fontWeight: 600 }}>{label}</div>
    </div>
  );
};

export const AndroidApp: React.FC = () => {
  const left = useEnter(10, 30);
  const feats = ["Voice keyboard for every app", "Live text in 99 languages", "✨ Voice edit", "Undo · per-app styles", "Insights & searchable history"];
  return (
    <AbsoluteFill>
      <Background hue="violet" />
      <div style={{ ...left, position: "absolute", left: 120, top: 260, width: 640, fontFamily: FONT }}>
        <div style={{ fontSize: 26, color: C.accentLight, fontWeight: 600, letterSpacing: 6 }}>FLOWKEY FOR ANDROID</div>
        <div style={{ fontSize: 76, fontWeight: 800, color: C.text, lineHeight: 1.05, marginTop: 16, letterSpacing: -2 }}>
          Your voice is the keyboard.
        </div>
        <div style={{ marginTop: 40, display: "flex", flexDirection: "column", gap: 18 }}>
          {feats.map((f, i) => {
            const e = useEnter(30 + i * 8, 20);
            return (
              <div key={f} style={{ ...e, fontSize: 32, color: C.text, display: "flex", gap: 16, alignItems: "center" }}>
                <span style={{ color: C.green, fontSize: 30 }}>✓</span>
                {f}
              </div>
            );
          })}
        </div>
      </div>
      <Phone src="img/android-home.png" delay={14} x={820} rot={-4} label="Home" />
      <Phone src="img/android-keyboard.png" delay={24} x={1170} rot={0} label="Keyboard" />
      <Phone src="img/android-history.png" delay={34} x={1520} rot={4} label="History" />
    </AbsoluteFill>
  );
};
