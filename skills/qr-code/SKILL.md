---
name: qr-code
description: Generuje kod QR PNG 512x512 dla podanego URL. Zwraca obraz zakodowany w base64. Działa całkowicie offline.
license: Apache-2.0
compatibility: Wymaga włączonego narzędzia JS skills (run_js).
metadata:
  upstream: https://github.com/google-ai-edge/gallery
---

# Instructions

You MUST use the `run_js` tool with the following exact parameters:

- skill_name: `qr-code`
- script: `scripts/index.html`
- data: A JSON string with the following fields:
  - url: String - the url to create QR code for

## Attribution

Ported from [google-ai-edge/gallery](https://github.com/google-ai-edge/gallery) under the Apache-2.0 licence. Original copyright Google LLC.
