// This is editable artwork, not a required style. Keep draw deterministic: time comes from the renderer.
// Talking scenes draw a transparent lower third. Motion scenes draw the full frame.
const ease = t => 1 - (1 - Math.max(0, Math.min(1, t))) ** 3;
function text(ctx, value, x, y, maxWidth, size, color, weight = 600) {
  let current = size;
  do { ctx.font = `${weight} ${current}px sans-serif`; if (ctx.measureText(value).width <= maxWidth) break; current -= 1; } while (current > 14);
  ctx.fillStyle = color; ctx.fillText(value, x, y);
}
function lines(ctx, value, maxWidth, size) {
  ctx.font = `700 ${size}px sans-serif`;
  const units = /\s/.test(value) ? value.split(/\s+/) : Array.from(value), join = /\s/.test(value) ? ' ' : '';
  const rows = []; let row = '';
  for (const unit of units) { const next = row ? row + join + unit : unit; if (ctx.measureText(next).width > maxWidth && row) { rows.push(row); row = unit; } else row = next; }
  if (row) rows.push(row); return rows;
}
export function draw(ctx, scene, time, { width: w, height: h }) {
  const scale = Math.min(w, h) / 720, pad = 54 * scale;
  const enter = ease(time / 0.45), exit = Math.min(1, Math.max(0, scene.duration - time) / 0.22);
  ctx.globalAlpha = enter * exit;
  if (scene.kind === 'talking') {
    const bh = 100 * scale, by = h - pad - bh + (1 - enter) * 16;
    ctx.fillStyle = 'rgba(15, 28, 38, 0.92)'; ctx.fillRect(pad, by, w - 2 * pad, bh);
    ctx.fillStyle = '#d6f27d'; ctx.fillRect(pad, by, 5 * scale, bh);
    text(ctx, scene.heading, pad + 24 * scale, by + 62 * scale, w - 2 * pad - 48 * scale, 32 * scale, '#ffffff');
    return;
  }
  ctx.globalAlpha = 1; ctx.fillStyle = '#f5f3ec'; ctx.fillRect(0, 0, w, h);
  ctx.globalAlpha = enter * exit;
  const titleSize = (w < h ? 45 : 55) * scale;
  const titleLines = lines(ctx, scene.heading, w - 2 * pad, titleSize);
  titleLines.forEach((line, i) => text(ctx, line, pad, pad + 60 * scale + i * titleSize * 1.2, w - 2 * pad, titleSize, '#142b33', 700));
  const top = pad + 95 * scale + titleLines.length * titleSize * 1.2;
  const points = scene.points || [scene.heading];
  const gap = 18 * scale, available = h - top - pad;
  const cardH = Math.min(120 * scale, (available - gap * (points.length - 1)) / points.length);
  points.forEach((point, i) => {
    const progress = ease((time - 0.25 - i * 0.35) / 0.45);
    ctx.globalAlpha = progress * exit;
    const y = top + i * (cardH + gap) + (1 - progress) * 18;
    ctx.fillStyle = i === 0 ? '#d6f27d' : '#ffffff'; ctx.fillRect(pad, y, w - 2 * pad, cardH);
    text(ctx, String(i + 1).padStart(2, '0'), pad + 24 * scale, y + cardH / 2 + 10 * scale, 65 * scale, 25 * scale, '#4f6557', 500);
    text(ctx, point, pad + 90 * scale, y + cardH / 2 + 11 * scale, w - 2 * pad - 115 * scale, 30 * scale, '#142b33');
  });
}
