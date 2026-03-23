#!/bin/bash
# Downloads the latest emoji data from iamcal/emoji-data and updates the bundled resource.
set -euo pipefail

URL="https://raw.githubusercontent.com/iamcal/emoji-data/master/emoji.json"
DATA_FILE="src/main/resources/assets/minemoticon/emoji_data.json"
META_FILE="src/main/resources/assets/minemoticon/emoji_data.meta.json"

echo "Downloading emoji data..."
curl -sL --fail "$URL" -o "$DATA_FILE"

SIZE=$(wc -c < "$DATA_FILE" | tr -d ' ')
DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
COUNT=$(python3 -c "import json; print(len(json.load(open('$DATA_FILE'))))")

cat > "$META_FILE" <<EOF
{
    "source": "$URL",
    "updated": "$DATE",
    "entries": $COUNT,
    "size": $SIZE
}
EOF

echo "Updated: $COUNT entries, ${SIZE} bytes"
echo "Meta: $META_FILE"
