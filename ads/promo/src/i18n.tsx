import React, { useContext } from "react";

export type Lang = "en" | "fr";

const STR = {
  en: {
    hook: ["Your music.", "Nonstop."],
    home: { k: "FOR YOU", l: ["A home that", "knows you"] },
    player: { k: "PLAYER", l: ["Your sound,", "full screen"] },
    voice: { k: "VOICE LEVEL", l: ["Turn the vocals", "down. Sing."] },
    replay: { k: "REPLAY", l: ["Your month", "in music"] },
    ketmix: { k: "KETMIX", l: ["Every song mixes", "into the next"] },
    editor: { k: "KETMIX EDITOR", l: ["Or draw the", "transition yourself"] },
    dj: "The DJ built into Ketvoice",
    words: ["Canvas", "Lyrics", "Discover", "And more."],
    tagline: "Your music, mixed like a DJ.",
    free: "Free on Android",
    vocals: "vocals",
    credits: ["Phone model: SabasGT", "CC BY 4.0 · colours changed", "Music: ElevenLabs Music"],
    wHook: ["Why use five apps for your music?", "What if they were one?"],
    wHome: "Made for you. Every single day.",
    wPlayer: "Every album, in full screen.",
    wLyrics: "Lyrics, synced word by word.",
    wVoice: "Turn the vocals down. Sing along.",
    wKetmix: "Every song flows into the next.",
    wEditor: "Or draw the transition yourself.",
    wFree: ["Free.", "No ads.", "No subscription."],
    wWhere: "Android · Meta Quest · Web player",
  },
  fr: {
    hook: ["Ta musique.", "Sans pause."],
    home: { k: "POUR TOI", l: ["Un accueil qui", "te connaît"] },
    player: { k: "LECTEUR", l: ["Ton son,", "en plein écran"] },
    voice: { k: "NIVEAU DE VOIX", l: ["Baisse la voix.", "Chante."] },
    replay: { k: "REPLAY", l: ["Ton mois", "en musique"] },
    ketmix: { k: "KETMIX", l: ["Chaque titre se mixe", "au suivant"] },
    editor: { k: "ÉDITEUR KETMIX", l: ["Ou dessine", "ta transition"] },
    dj: "Le DJ intégré à Ketvoice",
    words: ["Canvas", "Paroles", "Découvertes", "Et bien plus."],
    tagline: "Ta musique, mixée comme un DJ.",
    free: "Gratuit sur Android",
    vocals: "voix",
    credits: ["Phone model : SabasGT", "CC BY 4.0 · couleurs modifiées", "Musique : ElevenLabs Music"],
    wHook: ["Pourquoi cinq applis pour ta musique ?", "Et si elles n'en faisaient qu'une ?"],
    wHome: "Fait pour toi. Chaque jour.",
    wPlayer: "Chaque album, en plein écran.",
    wLyrics: "Les paroles, mot à mot.",
    wVoice: "Baisse la voix. Chante.",
    wKetmix: "Chaque titre se fond dans le suivant.",
    wEditor: "Ou dessine la transition toi-même.",
    wFree: ["Gratuit.", "Sans pub.", "Sans abonnement."],
    wWhere: "Android · Meta Quest · Lecteur web",
  },
} as const;

export const LangCtx = React.createContext<Lang>("en");
export const useT = () => STR[useContext(LangCtx)];
