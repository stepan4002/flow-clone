import React from "react";
import { AbsoluteFill, useCurrentFrame } from "remotion";
import { Background, Chip, Headline, Panel, lerp, typed, useEnter } from "../components";
import { C, FONT, MONO } from "../theme";

const SAID = "hey can you send me the report by friday";

const CARDS = [
  { icon: "💬", app: "WhatsApp · Slack", style: "Casual", color: C.green, out: "Hey, can you send me the report by Friday", mono: false },
  { icon: "✉️", app: "Gmail · Outlook", style: "Formal", color: C.accent, out: "Hi, could you please send me the report by Friday? Thank you.", mono: false },
  { icon: "</>", app: "VS Code · Terminal", style: "Verbatim", color: C.violet, out: "hey can you send me the report by friday", mono: true },
];

const Card: React.FC<{ i: number }> = ({ i }) => {
  const frame = useCurrentFrame();
  const c = CARDS[i];
  const delay = 40 + i * 16;
  const enter = useEnter(delay, 60);
  const p = lerp(frame, [delay + 20, delay + 60], [0, 1]);
  return (
    <Panel style={{ ...enter, width: 520, height: 400, padding: 36, boxSizing: "border-box", display: "flex", flexDirection: "column", gap: 22 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 16 }}>
        <div
          style={{
            width: 64, height: 64, borderRadius: 18, background: `${c.color}33`, display: "flex", alignItems: "center",
            justifyContent: "center", fontSize: c.icon.length > 2 ? 24 : 34, fontWeight: 800, color: C.text, fontFamily: MONO,
          }}
        >
          {c.icon}
        </div>
        <div style={{ fontSize: 28, fontWeight: 600, color: C.text }}>{c.app}</div>
      </div>
      <div>
        <Chip color={c.color}>Style: {c.style}</Chip>
      </div>
      <div style={{ fontSize: 32, lineHeight: 1.4, color: C.text, fontFamily: c.mono ? MONO : FONT, minHeight: 140 }}>
        {typed(c.out, p)}
        {p < 1 && p > 0 && <span style={{ color: C.accentLight }}>▍</span>}
      </div>
    </Panel>
  );
};

export const Styles: React.FC = () => {
  const said = useEnter(16, 20);
  return (
    <AbsoluteFill>
      <Background hue="violet" />
      <Headline kicker="Styles per app" title="The right tone for every app — automatically" top={70} />
      <div style={{ ...said, position: "absolute", top: 300, width: "100%", textAlign: "center", fontFamily: FONT }}>
        <span style={{ fontSize: 26, color: C.muted, letterSpacing: 4, marginRight: 18 }}>YOU SAY</span>
        <span style={{ fontSize: 38, color: C.text, fontStyle: "italic" }}>“{SAID}”</span>
      </div>
      <div style={{ position: "absolute", top: 420, width: "100%", display: "flex", justifyContent: "center", gap: 44 }}>
        {CARDS.map((_, i) => <Card key={i} i={i} />)}
      </div>
      <div style={{ position: "absolute", bottom: 70, width: "100%", textAlign: "center", fontFamily: FONT, fontSize: 30, color: C.muted, ...useEnter(120, 20) }}>
        Rules work offline · with AI on, the text is rewritten in the app’s style
      </div>
    </AbsoluteFill>
  );
};
