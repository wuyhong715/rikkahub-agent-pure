---
name: text-spinner
description: Renderuje obracającą się w 3D etykietę tekstową wewnątrz webview. Zwraca względny URL webview, który powierzchnia czatu może osadzić.
license: Apache-2.0
compatibility: Wymaga włączonego narzędzia JS skills (run_js).
metadata:
  upstream: https://github.com/google-ai-edge/gallery
---

# Instructions

You MUST use the `run_js` tool with the following exact parameters:

- skill_name: `text-spinner`
- script: `scripts/index.html`
- data: A JSON string with the following fields:
  - label: The text string to spin on my head.

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
