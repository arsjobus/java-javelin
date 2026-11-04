#!/bin/sh
# Builds "Javelin IDE.app" into <project>/dist using jpackage. macOS only. Needs JDK 25+ (with jpackage) and Maven.
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
  command -v "$tool" >/dev/null 2>&1 || { echo "Missing '$tool'. Install JDK 25+ and Apache Maven first." >&2; exit 1; }
done

# JavaFX 25 needs JDK 23+, and the project is built for 25 (the long-term-support release), where JavaFX starts without
# the sun.misc.Unsafe warning. An older JDK would only fail later with a less helpful message.
jdk_major=$(java -version 2>&1 | sed -n 's/^[^"]*"\([0-9][0-9]*\).*/\1/p' | head -n 1)
if [ "${jdk_major:-0}" -lt 25 ]; then
  found="${jdk_major:+JDK $jdk_major}"
  echo "Javelin IDE needs JDK 25 or newer to build; found ${found:-no readable version}. Install JDK 25+ and try again." >&2
  exit 1
fi

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

# JavaFX is only supported when it is loaded as named modules (from the class path it prints "Unsupported JavaFX
# configuration"). The platform jars (classifier mac or mac-aarch64) become the runtime's JavaFX modules; the plain
# javafx-*.jar stubs beside them have the same module names and would clash, so they are dropped. Everything else
# (RichTextFX and its helpers, the app jar) stays on the class path.
rm -rf target/fx
mkdir -p target/fx
for platform_jar in target/dist/javafx-*-mac*.jar; do
  [ -e "$platform_jar" ] || continue
  mv "$platform_jar" target/fx/
done
rm -f target/dist/javafx-*.jar

# IconExporter only draws into BufferedImages; keep AWT headless so it does not try to register an AppKit app.
java -Djava.awt.headless=true -cp target/classes com.javelin.icon.IconExporter packaging/icons

# jpackage builds the app's own runtime with jlink. Naming the modules replaces its guess, so list everything the app
# uses: JavaFX, the Java SE API (desktop, prefs, nio, process, net), and the few extras the JDK keeps separate.
MODULES="javafx.controls,java.se,jdk.unsupported,jdk.charsets,jdk.localedata,jdk.zipfs,jdk.compiler"

rm -rf dist
mkdir -p dist
jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input target/dist \
  --main-jar "$jar" \
  --main-class com.javelin.Launcher \
  --icon packaging/icons/icon.icns \
  --module-path target/fx \
  --add-modules "$MODULES" \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options "--enable-native-access=javafx.graphics" \
  --dest dist

echo
echo "Built: $root/dist/$APP_NAME.app"
echo "Install it, and the Javelin terminal command, with: packaging/macos/install.sh"
