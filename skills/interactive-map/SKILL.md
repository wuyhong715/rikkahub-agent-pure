---
name: interactive-map
description: Wyświetla interaktywną mapę Google dla danej lokalizacji. Zwraca URL webview, który powierzchnia czatu może wyrenderować.
license: Apache-2.0
compatibility: Wymaga włączonego narzędzia JS skills (run_js) i dostępu do sieci.
metadata:
  upstream: https://github.com/google-ai-edge/gallery
---

# Interactive map

## Examples

- "Show [a place] on interactive map"
- "Find [a place] on interactive map"

## Instructions

Call the `run_js` tool with the following exact parameters:

- skill_name: `interactive-map`
- script: `scripts/index.html`
- data: A JSON string with the following field
  - location: The location to show on the map.

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
