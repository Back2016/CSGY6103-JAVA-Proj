#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist/macos"
INPUT_DIR="$DIST_DIR/input"
PORTABLE_DIR="$DIST_DIR/P2P File Sharing Portable"
APP_NAME="P2P File Sharing"
VERSION="1.0.0"
GUI_JAR="p2p-file-sharing-system-${VERSION}-gui.jar"
TRACKER_JAR="p2p-file-sharing-system-${VERSION}-tracker.jar"

cd "$ROOT_DIR"

rm -rf "$DIST_DIR"
mkdir -p "$INPUT_DIR"

echo "Building shaded application jars..."
mvn -DskipTests package

cp "target/$GUI_JAR" "$INPUT_DIR/"
mkdir -p "$PORTABLE_DIR/lib"
cp "target/$GUI_JAR" "$PORTABLE_DIR/lib/"
cp "target/$TRACKER_JAR" "$PORTABLE_DIR/lib/"
cp "$ROOT_DIR/packaging/macos/P2P File Sharing.command" "$PORTABLE_DIR/"
cp "$ROOT_DIR/packaging/macos/Start Tracker.command" "$PORTABLE_DIR/"
cp "$ROOT_DIR/packaging/macos/INSTALL.txt" "$PORTABLE_DIR/"
chmod +x "$PORTABLE_DIR/P2P File Sharing.command" "$PORTABLE_DIR/Start Tracker.command"

echo "Creating macOS app image..."
if jpackage \
  --type app-image \
  --dest "$DIST_DIR" \
  --name "$APP_NAME" \
  --input "$INPUT_DIR" \
  --main-jar "$GUI_JAR" \
  --main-class edu.neu.cs6103.p2p.ui.MainApp \
  --app-version "$VERSION" \
  --vendor "CS6103 Final Project"; then
  echo "App image created."

  echo "Creating app zip archive..."
  cd "$DIST_DIR"
  rm -f "${APP_NAME}.zip"
  zip -r "${APP_NAME}.zip" "${APP_NAME}.app" >/dev/null

  echo "Attempting DMG build..."
  if jpackage \
    --type dmg \
    --dest "$DIST_DIR" \
    --name "$APP_NAME" \
    --input "$INPUT_DIR" \
    --main-jar "$GUI_JAR" \
    --main-class edu.neu.cs6103.p2p.ui.MainApp \
    --app-version "$VERSION" \
    --vendor "CS6103 Final Project"; then
    echo "DMG created successfully."
  else
    echo "DMG creation failed, but the .app bundle and .zip archive are available."
  fi
else
  echo "Native app-image creation failed, so only the portable bundle will be produced."
fi

echo "Creating portable zip archive..."
cd "$DIST_DIR"
rm -f "P2P File Sharing Portable.zip"
zip -r "P2P File Sharing Portable.zip" "P2P File Sharing Portable" >/dev/null

echo "Artifacts available in $DIST_DIR"
