#!/bin/sh
# Installs "Javelin IDE.app" (built by build-app.sh) and the 'Javelin' terminal command.
#
#   packaging/macos/install.sh              install
#   packaging/macos/install.sh --uninstall  remove both again
#
# The app goes to /Applications (or ~/Applications if that is not writable). The command goes to /usr/local/bin if
# that exists and is writable, otherwise to ~/.local/bin (add it to your PATH if it is not there already).
set -eu

APP_NAME="Javelin IDE"
here=$(cd "$(dirname "$0")" && pwd -P)
root=$(cd "$here/../.." && pwd -P)

# JAVELIN_APPS_DIR and JAVELIN_BIN_DIR choose other target folders (for a per-user install, or for the tests, which
# must never touch the real /Applications).
apps_dir() {
  if [ -n "${JAVELIN_APPS_DIR-}" ]; then mkdir -p "$JAVELIN_APPS_DIR"; echo "$JAVELIN_APPS_DIR"; return; fi
  if [ -w /Applications ]; then echo /Applications; else mkdir -p "$HOME/Applications"; echo "$HOME/Applications"; fi
}

bin_dir() {
  if [ -n "${JAVELIN_BIN_DIR-}" ]; then mkdir -p "$JAVELIN_BIN_DIR"; echo "$JAVELIN_BIN_DIR"; return; fi
  if [ -d /usr/local/bin ] && [ -w /usr/local/bin ]; then echo /usr/local/bin; else mkdir -p "$HOME/.local/bin"; echo "$HOME/.local/bin"; fi
}

if [ "${1-}" = "--uninstall" ]; then
  if [ -n "${JAVELIN_APPS_DIR-}" ]; then set -- "$JAVELIN_APPS_DIR"; else set -- /Applications "$HOME/Applications"; fi
  for apps in "$@"; do rm -rf "$apps/$APP_NAME.app"; done
  # An older install used a lowercase command name (never written out here); remove that file too.
  if [ -n "${JAVELIN_BIN_DIR-}" ]; then set -- "$JAVELIN_BIN_DIR"; else set -- /usr/local/bin "$HOME/.local/bin"; fi
  for bin in "$@"; do rm -f "$bin/Javelin" "$bin/javelin"; done
  echo "Removed $APP_NAME and the Javelin command. Settings in ~/Library/Application Support/javelin-ide are kept."
  exit 0
fi

built="$root/dist/$APP_NAME.app"
if [ ! -d "$built" ]; then
  echo "Nothing to install: $built does not exist. Run packaging/macos/build-app.sh first." >&2
  exit 1
fi

apps=$(apps_dir)
rm -rf "$apps/$APP_NAME.app"
cp -R "$built" "$apps/"
echo "Installed $apps/$APP_NAME.app"

bin=$(bin_dir)
rm -f "$bin/javelin"   # an older install used a lowercase name; the command is called Javelin now
cp "$here/Javelin" "$bin/Javelin"
chmod 755 "$bin/Javelin"
echo "Installed $bin/Javelin"

case ":$PATH:" in
  *":$bin:"*) ;;
  *) echo "Note: $bin is not on your PATH. Add this line to ~/.zshrc:  export PATH=\"$bin:\$PATH\"" ;;
esac
echo "Try it:  cd some/project && Javelin"
