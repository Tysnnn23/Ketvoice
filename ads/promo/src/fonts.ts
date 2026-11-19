import { continueRender, delayRender, staticFile } from "remotion";
import { theme } from "./theme";

// Inter (SIL OFL), the font Ketvoice itself ships.
const handle = delayRender("Inter");
const face = new FontFace(theme.font, `url(${staticFile("fonts/inter_variable.ttf")}) format("truetype")`, { weight: "100 900" });
face.load().then((f) => { (document.fonts as unknown as { add: (x: FontFace) => void }).add(f); continueRender(handle); });
