#!/bin/sh
set -eu
cd "$(dirname "$0")"
if ! command -v java >/dev/null 2>&1; then
  echo "A Java JDK is required. Install JDK 25 or newer, then run this file again." >&2
  exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "Apache Maven is required. Install Maven, then run this file again." >&2
  exit 1
fi
exec mvn javafx:run
