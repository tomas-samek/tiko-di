#!/usr/bin/env bash
# Headless layout check of site/ at 375px in light and dark mode, plus desktop screenshots.
# Usage (from anywhere in the repo): bash .ai-skills/tiko-site-maintainer/scripts/check-site.sh
# Env: PORT (default 58432 — outside Windows' reserved ranges), CHROME (browser binary).
# Exit: 0 when every check passes in both schemes, 1 otherwise.
set -euo pipefail

root=$(git rev-parse --show-toplevel)
port=${PORT:-58432}
out=$(mktemp -d)

chrome=${CHROME:-}
if [ -z "$chrome" ]; then
  for c in google-chrome chromium chromium-browser \
           "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
           "/c/Program Files/Google/Chrome/Application/chrome.exe" \
           "/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"; do
    if command -v "$c" >/dev/null 2>&1 || [ -x "$c" ]; then chrome=$c; break; fi
  done
fi
[ -n "$chrome" ] || { echo "No Chrome/Chromium/Edge found; set CHROME=/path/to/browser" >&2; exit 1; }

# First interpreter that actually runs — on Windows, `python3` can be the non-working Store stub.
py=""
for c in python3 python py; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import http.server' >/dev/null 2>&1; then py=$c; break; fi
done
[ -n "$py" ] || { echo "No working Python found (needed for the local server)" >&2; exit 1; }
"$py" -m http.server "$port" --bind 127.0.0.1 --directory "$root" >/dev/null 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true' EXIT
for _ in 1 2 3 4 5 6 7 8 9 10; do
  curl -s -o /dev/null "http://127.0.0.1:$port/site/" && break
  sleep 0.5
done

native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

status=0
for scheme in light dark; do
  pref=$([ "$scheme" = light ] && echo 1 || echo 0)
  json=$("$chrome" --headless=new --disable-gpu --user-data-dir="$out/profile-$scheme" \
      --blink-settings=preferredColorScheme=$pref --virtual-time-budget=8000 --dump-dom \
      "http://127.0.0.1:$port/.ai-skills/tiko-site-maintainer/scripts/probe.html" 2>/dev/null \
    | sed -n 's/.*<pre id="out">\([^<]*\)<\/pre>.*/\1/p' | sed 's/&quot;/"/g')
  echo "$scheme: $json"
  case "$json" in *'"ok":true'*) ;; *) status=1 ;; esac
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --user-data-dir="$out/shot-$scheme" \
      --blink-settings=preferredColorScheme=$pref --window-size=1280,4200 --virtual-time-budget=6000 \
      --screenshot="$(native "$out/desktop-$scheme.png")" "http://127.0.0.1:$port/site/" >/dev/null 2>&1 || true
done

echo "desktop screenshots: $out/desktop-light.png $out/desktop-dark.png"
[ "$status" -eq 0 ] && echo "PASS" || echo "FAIL — see the JSON above"
exit "$status"
