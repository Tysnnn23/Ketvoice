// Runs inside the target page via Playwright's browser_evaluate tool.
// Walks the full DOM (not just viewport), samples computed styles, and returns
// a JSON-friendly snapshot of the design surface: colors, typography, layout,
// effects, cards, images, spacing, section gaps, and the first major grid.
//
// Pass the entire contents of this file as the `function` parameter to
// mcp__playwright__browser_evaluate. The runtime returns the value.
//
// Output contract (matches what step1-measure.md expects):
//   {
//     url, title, viewport, errors[],
//     colors: { pageBackground, backgroundColors[{value,count,areaPct}],
//               textColors[{value,count}], accentCandidates[{value,count}] },
//     typography: { uniqueFamilies[{value,count}], headings{h1..h6}, body,
//                   sizeDistribution[{value,count}], weightDistribution[{value,count}] },
//     layout: { bodyPadding, containerMaxWidth, containerPadding },
//     effects: { radii[{value,count}], shadows[{value,count}], transitions[] },
//     cards: [{ selector, width, height, ratio, radius, shadow }],
//     images: [{ src, naturalWidth, naturalHeight, ratio, role }],
//     spacingSamples: [{ tag, margin, padding }],
//     spacingDistribution: [{value,count}],
//     sectionGaps: [{ between, gap }],
//     grid: { columns, gap, template } | null, gridCount,
//     buttons: [{ fontSize, fontWeight, letterSpacing, textTransform, borderRadius, padding }],
//     focusVisible: boolean, reducedMotion: boolean,
//     truncated?: boolean
//   }

() => {
  // Style sampling scans the whole page — high limit so footers/late sections aren't missed.
  const MAX_STYLE_ELEMENTS = 8000;
  // Card/grid detection only needs a representative viewport-first sample.
  const MAX_CARD_ELEMENTS = 2000;
  const TOP_COLORS = 8;
  const TOP_RADII = 8;
  const TOP_SHADOWS = 6;

  const data = {
    url: location.href,
    title: document.title,
    viewport: { width: window.innerWidth, height: window.innerHeight },
    errors: [],
  };

  // ---------- helpers ----------

  // isRendered: element exists in the layout (non-zero dimensions) and isn't CSS-hidden.
  // Does NOT require the element to be in the current viewport — captures below-fold elements.
  const isRendered = (el) => {
    const s = getComputedStyle(el);
    if (s.display === "none" || s.visibility === "hidden" || s.opacity === "0") return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };

  // isInViewport: element is rendered AND currently on-screen (for geometry tasks).
  const isInViewport = (el) => {
    if (!isRendered(el)) return false;
    const r = el.getBoundingClientRect();
    return r.bottom > 0 && r.top < window.innerHeight;
  };

  const bump = (map, key) => {
    if (!key) return;
    map.set(key, (map.get(key) || 0) + 1);
  };

  const safeSelector = (el) => {
    const tag = el.tagName.toLowerCase();
    const cls =
      el.className && typeof el.className === "string" && el.className.trim()
        ? "." + el.className.trim().split(/\s+/).slice(0, 2).join(".")
        : "";
    return tag + cls;
  };

  // ---------- gather elements (two pools) ----------
  // styleEls: full-page rendered elements for color/font/effect/section sampling.
  // viewportEls: viewport-only elements for card and grid detection.
  const styleEls = [];
  const viewportEls = [];
  const all = document.body ? document.body.querySelectorAll("*") : [];
  for (let i = 0; i < all.length; i++) {
    const el = all[i];
    if (styleEls.length < MAX_STYLE_ELEMENTS && isRendered(el)) styleEls.push(el);
    if (viewportEls.length < MAX_CARD_ELEMENTS && isInViewport(el)) viewportEls.push(el);
    if (styleEls.length >= MAX_STYLE_ELEMENTS && viewportEls.length >= MAX_CARD_ELEMENTS) break;
  }

  // Keep a single reference for backwards-compat (used by spacing/section/grid below).
  const visible = styleEls;

  const skipBg = (c) => !c || c === "rgba(0, 0, 0, 0)" || c === "transparent";

  // ---------- single-pass style sampling (colors + typography + effects) ----------
  try {
    const bgCounts = new Map();
    const fgCounts = new Map();
    const bgArea = new Map();
    const families = new Map();
    const headings = {};
    const sizeCounts = new Map();
    const weightCounts = new Map();
    const radiiCount = new Map();
    const shadowCount = new Map();
    const transitionSet = new Set();
    const spacingCounts = new Map();

    const bodyBg = document.body ? getComputedStyle(document.body).backgroundColor : "rgb(255, 255, 255)";
    let totalArea = 0;

    for (const el of visible) {
      const s = getComputedStyle(el);
      const r = el.getBoundingClientRect();

      // colors
      if (!skipBg(s.backgroundColor)) {
        bump(bgCounts, s.backgroundColor);
        const a = r.width * r.height;
        bgArea.set(s.backgroundColor, (bgArea.get(s.backgroundColor) || 0) + a);
        totalArea += a;
      }
      if (el.childNodes && Array.from(el.childNodes).some((n) => n.nodeType === 3 && n.textContent.trim())) {
        bump(fgCounts, s.color);
      }

      // typography
      const family = (s.fontFamily || "").split(",")[0].replace(/['"]/g, "").trim();
      if (family) bump(families, family);
      const tag = el.tagName.toLowerCase();
      if (/^h[1-6]$/.test(tag) && !headings[tag]) {
        headings[tag] = {
          size: s.fontSize, weight: s.fontWeight, lineHeight: s.lineHeight,
          letterSpacing: s.letterSpacing, family,
        };
      }
      if (el.childNodes && Array.from(el.childNodes).some((n) => n.nodeType === 3 && n.textContent.trim())) {
        bump(sizeCounts, s.fontSize);
        bump(weightCounts, s.fontWeight);
      }

      // effects
      if (s.borderRadius && s.borderRadius !== "0px") bump(radiiCount, s.borderRadius);
      if (s.boxShadow && s.boxShadow !== "none") bump(shadowCount, s.boxShadow);
      if (s.transition && s.transition !== "all 0s ease 0s" && s.transition !== "none 0s ease 0s") {
        transitionSet.add(s.transition.slice(0, 120));
      }

      // spacing distribution
      for (const prop of ["gap", "paddingTop", "paddingBottom", "marginTop", "marginBottom"]) {
        const v = s[prop];
        if (v && v !== "0px" && v.endsWith("px")) bump(spacingCounts, v);
      }
    }

    // accent candidates
    const accentCounts = new Map();
    document.querySelectorAll("button, a, [role='button']").forEach((el) => {
      if (!isRendered(el)) return;
      const s = getComputedStyle(el);
      if (!skipBg(s.backgroundColor)) bump(accentCounts, s.backgroundColor);
      if (s.color) bump(accentCounts, s.color);
    });

    // button micro-typography
    const btnMap = new Map();
    document.querySelectorAll("button, a[role='button'], [role='button']").forEach((el) => {
      if (!isRendered(el)) return;
      const s = getComputedStyle(el);
      const key = [s.fontSize, s.fontWeight, s.letterSpacing, s.textTransform, s.borderRadius].join("|");
      if (!btnMap.has(key)) {
        btnMap.set(key, {
          fontSize: s.fontSize, fontWeight: s.fontWeight, letterSpacing: s.letterSpacing,
          textTransform: s.textTransform, borderRadius: s.borderRadius,
          paddingTop: s.paddingTop, paddingLeft: s.paddingLeft,
        });
      }
    });

    const topWithCount = (map, n) =>
      Array.from(map.entries()).sort((a, b) => b[1] - a[1]).slice(0, n)
        .map(([value, count]) => ({ value, count }));

    data.colors = {
      pageBackground: bodyBg,
      backgroundColors: topWithCount(bgCounts, TOP_COLORS).map((e) => ({
        ...e, areaPct: totalArea > 0 ? +(e.value && bgArea.get(e.value) ? (bgArea.get(e.value) / totalArea * 100).toFixed(1) : 0) : 0,
      })),
      textColors: topWithCount(fgCounts, 6),
      accentCandidates: topWithCount(accentCounts, 6),
    };

    let bodyEl = document.querySelector("main p, article p");
    if (!bodyEl) bodyEl = document.querySelector("p");
    let bodyType = null;
    if (bodyEl) {
      const s = getComputedStyle(bodyEl);
      bodyType = {
        size: s.fontSize, weight: s.fontWeight, lineHeight: s.lineHeight,
        family: (s.fontFamily || "").split(",")[0].replace(/['"]/g, "").trim(),
        maxWidth: s.maxWidth,
      };
    }

    data.typography = {
      uniqueFamilies: topWithCount(families, 6),
      headings,
      body: bodyType,
      sizeDistribution: topWithCount(sizeCounts, 12),
      weightDistribution: topWithCount(weightCounts, 8),
    };

    data.effects = {
      radii: topWithCount(radiiCount, TOP_RADII),
      shadows: topWithCount(shadowCount, TOP_SHADOWS).map((e) => ({ ...e, value: e.value.slice(0, 160) })),
      transitions: Array.from(transitionSet).slice(0, 6),
    };

    data.spacingDistribution = topWithCount(spacingCounts, 15);
    data.buttons = Array.from(btnMap.values()).slice(0, 5);
  } catch (e) {
    data.errors.push({ section: "colors+typography+effects", error: e.message });
  }

  // ---------- layout ----------
  try {
    const findFirst = (selectors) => {
      for (const sel of selectors) { const el = document.querySelector(sel); if (el) return el; }
      return null;
    };
    const container = findFirst(["main", "[class*='container']", "[class*='wrapper']", "[class*='Container']", "article", "header", "section"]);
    const bodyStyle = document.body ? getComputedStyle(document.body) : null;
    data.layout = {
      bodyPadding: bodyStyle ? bodyStyle.padding : "unknown",
      containerMaxWidth: container ? getComputedStyle(container).maxWidth : "none",
      containerPadding: container ? getComputedStyle(container).padding : "none",
      containerWidth: container ? Math.round(container.getBoundingClientRect().width) + "px" : "unknown",
    };
  } catch (e) {
    data.errors.push({ section: "layout", error: e.message });
  }

  // ---------- card heuristics (viewport elements only) ----------
  try {
    const cardCandidates = [];
    for (const el of viewportEls) {
      const s = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      const hasRound = parseFloat(s.borderRadius) > 0;
      const hasShadowOrBorder = s.boxShadow !== "none" || (s.borderWidth && parseFloat(s.borderWidth) > 0);
      const sized = r.width > 140 && r.width < 900 && r.height > 80 && r.height < 700;
      const hasChildren = el.children && el.children.length > 0;
      if (hasRound && hasShadowOrBorder && sized && hasChildren) {
        cardCandidates.push({ el, r, s });
      }
      if (cardCandidates.length >= 12) break;
    }
    data.cards = cardCandidates.slice(0, 8).map(({ el, r, s }) => ({
      selector: safeSelector(el),
      width: Math.round(r.width) + "px",
      height: Math.round(r.height) + "px",
      ratio: (r.width / r.height).toFixed(2) + ":1",
      radius: s.borderRadius,
      shadow: s.boxShadow === "none" ? null : (s.boxShadow || "").slice(0, 160),
    }));
  } catch (e) {
    data.errors.push({ section: "cards", error: e.message });
  }

  // ---------- images ----------
  try {
    data.images = Array.from(document.querySelectorAll("img"))
      .filter((img) => img.naturalWidth > 80 && img.naturalHeight > 80)
      .slice(0, 10)
      .map((img) => {
        const r = img.getBoundingClientRect();
        const role = r.width > 800 ? "hero" : r.width > 300 ? "feature" : "thumbnail";
        let src = img.currentSrc || img.src;
        if (src.startsWith("data:")) {
          const mime = src.slice(5, src.indexOf(";"));
          const kb = Math.round(src.length * 0.75 / 1024);
          src = "data-uri (" + mime + ", ~" + kb + "KB)";
        } else {
          src = src.slice(0, 100);
        }
        return {
          src,
          naturalWidth: img.naturalWidth,
          naturalHeight: img.naturalHeight,
          ratio: (img.naturalWidth / img.naturalHeight).toFixed(2) + ":1",
          renderedWidth: Math.round(r.width) + "px",
          role,
        };
      });
  } catch (e) {
    data.errors.push({ section: "images", error: e.message });
  }

  // ---------- spacing samples ----------
  try {
    const sampleTags = ["section", "article", "h1", "h2", "h3", "p", "ul", "button", "nav", "footer"];
    data.spacingSamples = sampleTags
      .map((tag) => {
        const el = document.querySelector(tag);
        if (!el || !isRendered(el)) return null;
        const s = getComputedStyle(el);
        return { tag, margin: s.margin, padding: s.padding, gap: s.gap || null };
      })
      .filter(Boolean);
  } catch (e) {
    data.errors.push({ section: "spacingSamples", error: e.message });
  }

  // ---------- section gaps ----------
  try {
    const sections = Array.from(document.querySelectorAll("section, main > article, main > div, main > section"))
      .filter((el) => isRendered(el) && el.getBoundingClientRect().height > 200)
      .slice(0, 10);
    data.sectionGaps = [];
    for (let i = 0; i < sections.length - 1; i++) {
      const a = sections[i].getBoundingClientRect();
      const b = sections[i + 1].getBoundingClientRect();
      const gap = b.top - a.bottom;
      if (gap > 0 && gap < 600) {
        data.sectionGaps.push({
          between: sections[i].tagName.toLowerCase() + "[" + i + "]→" + sections[i + 1].tagName.toLowerCase() + "[" + (i + 1) + "]",
          gap: Math.round(gap),
        });
      }
    }
  } catch (e) {
    data.errors.push({ section: "sectionGaps", error: e.message });
  }

  // ---------- largest significant grid ----------
  try {
    let grid = null;
    let gridArea = 0;
    let gridCount = 0;
    for (const el of styleEls) {
      const s = getComputedStyle(el);
      if (s.display === "grid") {
        const cols = s.gridTemplateColumns.split(/\s+(?![^()]*\))/).filter(Boolean);
        if (cols.length >= 2) {
          gridCount++;
          const r = el.getBoundingClientRect();
          const area = r.width * r.height;
          if (area > gridArea) {
            gridArea = area;
            grid = {
              columns: cols.length,
              gap: s.gap || s.gridGap || "none",
              template: s.gridTemplateColumns.slice(0, 120),
              selector: safeSelector(el),
            };
          }
        }
      }
    }
    data.grid = grid;
    data.gridCount = gridCount;
  } catch (e) {
    data.errors.push({ section: "grid", error: e.message });
  }

  // ---------- interaction signals ----------
  try {
    let focusVisible = false;
    let reducedMotion = false;
    for (const sheet of document.styleSheets) {
      let rules;
      try { rules = sheet.cssRules; } catch (_) { continue; }
      if (!rules) continue;
      for (const rule of rules) {
        const text = rule.cssText || "";
        if (!focusVisible && text.includes(":focus-visible")) focusVisible = true;
        if (!reducedMotion && text.includes("prefers-reduced-motion")) reducedMotion = true;
        if (focusVisible && reducedMotion) break;
      }
      if (focusVisible && reducedMotion) break;
    }
    data.focusVisible = focusVisible;
    data.reducedMotion = reducedMotion;
  } catch (e) {
    data.errors.push({ section: "interaction", error: e.message });
  }

  // ---------- payload size guard (60KB limit, tiered trimming) ----------
  const measure = () => JSON.stringify(data).length;
  if (measure() > 60000) {
    // Tier 1: low-impact fields
    if (data.spacingSamples) data.spacingSamples = data.spacingSamples.slice(0, 10);
    if (data.images) data.images = data.images.slice(0, 4);
    if (data.effects && data.effects.transitions) data.effects.transitions = data.effects.transitions.slice(0, 3);
    data.truncated = true;
  }
  if (measure() > 60000) {
    // Tier 2: mid-impact fields
    if (data.cards) data.cards = data.cards.slice(0, 4);
    if (data.effects && data.effects.shadows) data.effects.shadows = data.effects.shadows.slice(0, 4);
  }
  if (measure() > 60000) {
    // Tier 3: core fields (last resort)
    if (data.colors && data.colors.backgroundColors) data.colors.backgroundColors = data.colors.backgroundColors.slice(0, 6);
    if (data.colors && data.colors.textColors) data.colors.textColors = data.colors.textColors.slice(0, 4);
  }

  return data;
};
