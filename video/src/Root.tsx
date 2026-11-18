import React from "react";
import { Composition } from "remotion";
import { FlowCloneVideo, TOTAL_FRAMES } from "./FlowCloneVideo";
import { FPS, H, W } from "./theme";

export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="FlowClone" component={FlowCloneVideo} durationInFrames={TOTAL_FRAMES} fps={FPS} width={W} height={H} />
  </>
);
