#!/bin/bash
# Downloads the latest emoji data and font, updates bundled resources.
set -euo pipefail

DATA_URL="https://raw.githubusercontent.com/iamcal/emoji-data/master/emoji.json"
FONT_URL="https://github.com/13rac1/twemoji-color-font/releases/download/v15.1.0/TwitterColorEmoji-SVGinOT-Linux-15.1.0.tar.gz"
DATA_FILE="src/main/resources/assets/minemoticon/emoji_data.json"
META_FILE="src/main/resources/assets/minemoticon/emoji_data.meta.json"
FONT_FILE="src/main/resources/assets/minemoticon/twemoji.ttf"

echo "Downloading emoji data..."
curl -sL --fail "$DATA_URL" -o "$DATA_FILE"

SIZE=$(wc -c < "$DATA_FILE" | tr -d ' ')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
COUNT=$(python3 -c "import json; print(len(json.load(open('$DATA_FILE'))))")

cat > "$META_FILE" <<EOF
{
    "source": "$DATA_URL",
    "updated": "$DATE",
    "entries": $COUNT,
    "size": $SIZE
}
EOF

echo "Updated emoji data: $COUNT entries, ${SIZE} bytes"

echo "Downloading Twemoji SVGinOT font..."
curl -sL --fail "$FONT_URL" | tar -xzO "TwitterColorEmoji-SVGinOT-Linux-15.1.0/TwitterColorEmoji-SVGinOT.ttf" > "$FONT_FILE"
FONT_SIZE=$(wc -c < "$FONT_FILE" | tr -d ' ')
echo "Updated font: ${FONT_SIZE} bytes"
