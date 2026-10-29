#!/bin/sh
# Builds "Javelin IDE.app" into <project>/dist using jpackage. macOS only. Needs JDK 21+ (with jpackage) and Maven.
#
#   packaging/macos/build-app.sh                run the tests, then build
#   SKIP_TESTS=1 packaging/macos/build-app.sh   build without running the tests
#   APP_VERSION=1.2.0 packaging/macos/build-app.sh
#
# The app is built for the architecture of the JDK you run this with (Apple Silicon JDK -> Apple Silicon app).
set -eu

APP_NAME="Javelin IDE"
# jpackage refuses a macOS app version that starts with 0, so the bundle version is kept apart from the Maven one.
APP_VERSION="${APP_VERSION:-1.0.0}"

here=$(cd "$(dirname "$0")" && pwd -P)
root=$(cd "$here/../.." && pwd -P)
cd "$root"

if [ "$(uname -s)" != "Darwin" ]; then
  echo "build-app.sh builds the macOS app and must be run on a Mac." >&2
  exit 1
fi
for tool in java jpackage mvn; do
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing '$tool'. Install JDK 21+ and Apache Maven first." >&2; exit 1; }
done

if [ "${SKIP_TESTS:-0}" = "1" ]; then
  mvn -q clean package -DskipTests
else
  mvn -q clean package
fi

version=$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout)
jar="Javelin-IDE-$version.jar"

rm -rf target/dist
mkdir -p target/dist
mvn -q dependency:copy-dependencies -DoutputDirectory=target/dist -DincludeScope=runtime
cp "target/$jar" target/dist/

# IconExporter only draws into BufferedImages; keep AWT headless so it does not try to register an AppKit app.
java -Djava.awt.headless=true -cp target/classes com.javelin_ide.icon.IconExporter packaging/icons

rm -rf dist
mkdir -p dist
jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input target/dist \
  --main-jar "$jar" \
  --main-class com.javelin_ide.Launcher \
  --icon packaging/icons/icon.icns \
  --java-options "-Dfile.encoding=UTF-8" \
  --dest dist

echo
echo "Built: $root/dist/$APP_NAME.app"
echo "Install it, and the Javelin terminal command, with: packaging/macos/install.sh"
