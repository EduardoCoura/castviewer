# CastViewer Design System v2.0 — Craft Minimalist

> **Design Philosophy & Visual Architecture**  
> Inspired by the precision engineering of **Linear**, the tactical speed of **Raycast**, and the industrial physical tactile sensibility of **Teenage Engineering**.

---

## 1. Executive Manifesto: The Anti-AI Cliché Standard

Generic AI-generated interfaces have flooded modern software with predictable, superficial tropes:
- **Zero Purple/Cyan Neon Glows:** No diffuse 30px radial gradients, no `drop-shadow(0 0 16px rgba(99, 102, 241, 0.5))`, no pseudo-cyberpunk electric haze.
- **Zero Rainbow Gradient Cards:** No multi-stop decorative linear gradients (`linear-gradient(135deg, #6366F1, #EC4899)`) masking poor informational hierarchy.
- **Zero Heavy Translucent Glassmorphism:** No 40px blurry backdrop filters simulating plastic sheets.
- **Zero Cluttered Boxes-Inside-Boxes:** No Russian-doll nesting of 5 layers of bordered cards that suffocate content.

### The Craft Standard
1. **Engineered Monochrome Foundation:** A calibrated matte charcoal studio canvas (`#0A0A0C`) with elevated matte planes (`#121215`). Depth is established through 1px precision hairlines (`rgba(255, 255, 255, 0.07)`) and subtle surface tonal shifts, never by blur or saturation.
2. **Surgical Accents:** Accent color is an instrument of signaling, not decoration.
   - **Signal Orange (`#FF5A1F`)** is strictly reserved for the active live broadcast / recording transmission state (Teenage Engineering hardware cue).
   - **Signal Green (`#22C55E`)** is strictly a crisp solid 6px dot denoting a healthy network handshake.
   - **Signal Amber (`#EAB308`)** denotes transient connection handshakes or high latency warnings.
3. **Tactile High-Contrast Controls:** The primary action is a pure, solid `#FAFAFA` keycap plate with `#09090B` typography (Apple / Linear style). It feels like a physical milled key switch on a studio mixing console.
4. **Tabular Numerics & Tight Tracking:** Telemetry (RTT, FPS, Bitrate, VU dB levels) uses fixed-width tabular figures (`font-variant-numeric: tabular-nums lining-nums`) so numbers never jitter or jump layout during real-time streaming.

---

## 2. Foundational Tokens

### 2.1 Matte Studio Monochrome Palette
| Token | Value | Role |
| :--- | :--- | :--- |
| `--cv-bg-canvas` | `#0A0A0C` | Deep matte charcoal base (not pitch black `#000000`) |
| `--cv-bg-surface` | `#121215` | Subtle elevated component surface (cards, bars) |
| `--cv-bg-surface-hover` | `#19191D` | Interactive hover plane |
| `--cv-bg-surface-active` | `#222227` | Pressed state / active plate |
| `--cv-bg-inset` | `#0E0E11` | Recessed wells, inputs, segment rails, counters |
| `--cv-bg-glass` | `rgba(18, 18, 21, 0.92)` | Dock overlay with sharp 8px optical backdrop blur |
| `--cv-border-subtle` | `rgba(255, 255, 255, 0.07)` | Precision 1px hairline border |
| `--cv-border-strong` | `rgba(255, 255, 255, 0.16)` | Interactive border on hover / focused state |
| `--cv-border-focus` | `#FAFAFA` | Keyboard navigation and high-contrast outline |

### 2.2 Text & Information Hierarchy
| Token | Value | Contrast Ratio | Usage |
| :--- | :--- | :--- | :--- |
| `--cv-text-primary` | `#F4F4F5` | 15.2:1 (AAA) | High-contrast headings, primary CTA text, selected labels |
| `--cv-text-secondary` | `#A1A1AA` | 8.1:1 (AAA) | Descriptive copy, unselected tabs, secondary controls |
| `--cv-text-muted` | `#63636E` | 4.6:1 (AA) | Placeholders, keyboard hints, disabled states, VU tick marks |

### 2.3 Surgical Accents & Indicators
| Token | Value | Role & Strict Usage Rule |
| :--- | :--- | :--- |
| `--cv-cta-bg` | `#FAFAFA` | Pure solid light keycap for default primary action plate |
| `--cv-cta-text` | `#09090B` | Deep black high-contrast text on primary plate |
| `--cv-signal-orange` | `#FF5A1F` | **Industrial Signal Orange:** Active transmission / recording only |
| `--cv-signal-orange-hover`| `#E04B14` | Hover state for stop transmission button |
| `--cv-live` | `#22C55E` | **Signal Green:** Solid 6px connection dot, verified LAN connection |
| `--cv-warning` | `#EAB308` | **Signal Amber:** Connecting state, WebRTC ICE candidate gathering |
| `--cv-danger` | `#EF4444` | **Signal Crimson:** Packet drop alert, fatal signaling failure |

### 2.4 Typography System
```css
/* Core Typography Stack */
--cv-font-sans: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
--cv-font-mono: "SF Mono", "Cascadia Code", ui-monospace, Consolas, monospace;

/* Heading Scale */
font-family: var(--cv-font-sans);
font-size: 13px;
font-weight: 600;
letter-spacing: -0.02em; /* Tight craft tracking */
color: var(--cv-text-primary);

/* Telemetry & Readouts */
font-family: var(--cv-font-mono);
font-variant-numeric: tabular-nums lining-nums;
letter-spacing: -0.01em;
font-weight: 500;
```

### 2.5 Spatial Metrics & Micro-Geometry
- **Baseline Grid:** Strict 4px / 8px spatial rhythm.
- **Hairlines:** Exactly `1px solid var(--cv-border-subtle)` (0.5px physical on 2x Retina/HiDPI).
- **Corner Radii:**
  - Micro (`--radius-xs`): `4px` (tags, badge pills, tooltips)
  - Small (`--radius-sm`): `6px` (inputs, window controls, icon buttons)
  - Medium (`--radius-md`): `8px` (cards, device pills, source cards)
  - Large (`--radius-lg`): `12px` (dock container, dialogs)
  - Pill (`--radius-pill`): `9999px` (tab switcher rail, active indicators)
- **Shadows:** Milled studio elevation instead of diffuse clouds:
  - Subtle Plate: `0 1px 2px rgba(0, 0, 0, 0.45)`
  - Elevated Popover: `0 8px 24px -4px rgba(0, 0, 0, 0.65), 0 0 0 1px var(--cv-border-subtle)`

---

## 3. Component Architecture & Design Specs

### 3.1 Header Bar (`.topbar` / `.top-bar`)
- **Dimensions:** 48px fixed height, `#121215` background, border-bottom `1px solid var(--cv-border-subtle)`.
- **Left Branding:** Clean monochrome icon in `#F4F4F5`, app title in `14px` weight `600`, tracking `-0.02em`. Micro badge (`LAN`) styled as a recessed `#0E0E11` chip with 1px hairline border.
- **Center Navigation (`.tab-switcher`):**
  - Track: Recessed `#0E0E11` pill container, 1px border.
  - Active Tab (`.tab-pill.active`): Matte `#1E1E24` tile, crisp `1px solid rgba(255, 255, 255, 0.14)` border, pure `#F4F4F5` text, zero neon bloom.
  - Inactive Tab (`.tab-pill`): `#A1A1AA` text, transparent background, transitions to `#F4F4F5` on hover.
- **Right Status (`.status-pill`):**
  - Idle: Recessed chip, 6px muted gray dot `#63636E`, text "Pronto".
  - Connecting: Amber hairline `rgba(234, 179, 8, 0.3)`, solid `#EAB308` dot.
  - Streaming: Muted green hairline `rgba(34, 197, 94, 0.25)`, solid `#22C55E` dot, text "Transmitindo".
- **Window Controls (`.win-btn`):**
  - Milled square buttons (30×30px, 6px radius). Hover shows subtle `rgba(255, 255, 255, 0.06)`. Close button transitions to `#EF4444` on hover.

### 3.2 Fire TV Discovery Strip (`.devices-row`, `.device-card-pill` / `.device-pill`)
- Horizontal, scrollable strip of tactile micro-cards.
- **Unselected:** Inset `#0E0E11` surface, hairline border `rgba(255, 255, 255, 0.07)`, TV icon in `#A1A1AA`.
- **Hover:** Elevates to `#19191D`, border darkens to `rgba(255, 255, 255, 0.16)`.
- **Selected:** High-contrast tactile state. `#18181D` surface, border `1px solid rgba(255, 255, 255, 0.4)`, TV icon `#F4F4F5`. Radio circle fills with pure `#FAFAFA` and deep `#09090B` center dot.
- **IP Address:** Rendered in `font-variant-numeric: tabular-nums` monospace at `10px` in `#63636E`.

### 3.3 Source Picker Grid (`.sources-grid`, `.source-card`)
- 16:9 thumbnail previews for Screens and Application Windows.
- **Card Framing:** `#0E0E11` well with 1px hairline border and `8px` corner radius.
- **Thumbnail:** Black matte viewport (`#000000`) with crisp cover crop.
- **Selection State:** White hairline border (`1px solid #FAFAFA`). A 20×20px circular badge at the top-right displays a solid `#FAFAFA` background with `#09090B` checkmark. **Zero diffuse purple/blue glow.**
- **Footer Bar:** Compact 28px bar displaying the 16×16px application icon and truncated title in `#F4F4F5` (`11px`, weight `500`).

### 3.4 Control Dock & Primary CTA (`.stream-dock`, `.btn-main-cta` / `.btn-primary`)
- Fixed 68px bottom dock with dark translucent surface (`rgba(18, 18, 21, 0.94)`) and `1px solid var(--cv-border-subtle)` top hairline.
- **Left Telemetry Bar (`.telemetry-bar`):**
  - 4 compact measurement pills: `RTT`, `FPS`, `BITRATE`, `CODEC`.
  - Labels in `9px` uppercase bold `#63636E`.
  - Values in `11px` monospace `#F4F4F5` with `tabular-nums` ensuring constant visual width during packet fluctuations.
- **Primary Action Button (`.btn-main-cta`):**
  - **Idle / Ready State:** Milled solid `#FAFAFA` keycap plate with `#09090B` bold text. Crisp `1px solid rgba(255, 255, 255, 0.9)`. Hover softens to `#E4E4E7`. Active click applies `transform: translateY(1px)`.
  - **Connecting State:** Matte `#19191D` plate, border `1px solid rgba(234, 179, 8, 0.4)`, text `#EAB308`, spinning 14px ring.
  - **Active Transmission (Stop CTA):** **Signal Orange (`#FF5A1F`)** plate with crisp white `#FFFFFF` text. Bold, instantly recognizable, inspired by hardware record buttons.
  - **Disabled State:** `#161619` plate, border `1px solid rgba(255, 255, 255, 0.05)`, text `#63636E`, cursor `not-allowed`.

### 3.5 Audio VU Level Meter (Teenage Engineering Hardware Inspired)
- Real-time Web Audio API stereo monitor (`L` and `R` dual bars).
- **Housing:** Recessed `#050507` matte black chamber with `1px solid rgba(255, 255, 255, 0.08)` border.
- **VU Tracks:** 8px high precision channels. Discrete gradient segments:
  - `-40 dB to -10 dB`: Surgical Green `#22C55E` (clean signal)
  - `-10 dB to -3 dB`: Signal Amber `#EAB308` (nominal studio headroom)
  - `-3 dB to 0 dB`: Signal Orange `#FF5A1F` / Crimson `#EF4444` (clip threshold)
- **Decibel Readout:** Crisp tabular monospace (e.g. `-14.2 dB`) in `#22C55E`.

### 3.6 Settings Cards & Switches (`.settings-card`, `.cv-switch`)
- **Settings Group:** `#121215` card with 1px border. Headings in `-0.02em` tracking.
- **Tactile Switch:**
  - Track: Milled oval well (`#0E0E11`, border `1px solid rgba(255, 255, 255, 0.16)`).
  - Checked Track: Solid `#FAFAFA`.
  - Thumb: Solid `#09090B` circle moving 16px. Mechanical and tactile.
- **Quality Preset Options (`.preset-option`):**
  - Radio card: `#0E0E11` inset.
  - Selected state: `#19191D` elevated surface, border `1px solid rgba(255, 255, 255, 0.35)`. Radio dot fills with solid `#FAFAFA`.

---

## 4. Accessibility & Mechanical Precision

### 4.1 Contrast Standards
- Every text element complies with **WCAG 2.1 AA** (minimum 4.5:1 for body) and headings achieve **AAA** (> 7:1 against canvas).
- No low-contrast gray-on-gray unreadable text.

### 4.2 Windows High Contrast Mode (`forced-colors: active`)
```css
@media (forced-colors: active) {
  .btn-main-cta {
    forced-color-adjust: none;
    border: 2px solid ButtonText !important;
  }
  .source-card.selected,
  .preset-option.selected,
  .device-card-pill.selected {
    outline: 2px solid Highlight !important;
  }
  .tab-pill.active {
    outline: 2px solid Highlight !important;
  }
}
```

### 4.3 Motion Sensitivity (`prefers-reduced-motion`)
- Standard: Crisp, snappy 120ms transitions (`cubic-bezier(0.2, 0, 0, 1)`).
- Reduced Motion: All animations and transitions evaluate to `0.001ms`, providing immediate tactile state switching without layout reflows.

---

## 5. Token Summary Quick Reference

```css
:root {
  /* Canvas & Planes */
  --cv-bg-canvas: #0A0A0C;
  --cv-bg-surface: #121215;
  --cv-bg-surface-hover: #19191D;
  --cv-bg-surface-active: #222227;
  --cv-bg-inset: #0E0E11;
  --cv-bg-glass: rgba(18, 18, 21, 0.94);

  /* Hairlines */
  --cv-border-subtle: rgba(255, 255, 255, 0.07);
  --cv-border-strong: rgba(255, 255, 255, 0.16);
  --cv-border-focus: #FAFAFA;

  /* Typography */
  --cv-text-primary: #F4F4F5;
  --cv-text-secondary: #A1A1AA;
  --cv-text-muted: #63636E;

  /* Primary CTA Keycap */
  --cv-cta-bg: #FAFAFA;
  --cv-cta-text: #09090B;
  --cv-cta-hover: #E4E4E7;
  --cv-cta-active: #D4D4D8;

  /* Surgical Accents */
  --cv-signal-orange: #FF5A1F;
  --cv-signal-orange-hover: #E04B14;
  --cv-live: #22C55E;
  --cv-warning: #EAB308;
  --cv-danger: #EF4444;

  /* Radii */
  --radius-xs: 4px;
  --radius-sm: 6px;
  --radius-md: 8px;
  --radius-lg: 12px;
  --radius-pill: 9999px;
}
```
