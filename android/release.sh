#!/usr/bin/env bash
# Publish the current Android version as a GitHub release; the app finds it and offers the in-app update.
#   ./release.sh "What changed, in plain words"
# Needs: keystore.properties + the release key (same key as the installed app), gh logged in.
set -euo pipefail
cd "$(dirname "$0")"

ver=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
tag="v$ver"
notes="${1:-$(git log -1 --format=%s)}"

[ -f keystore.properties ] || { echo "keystore.properties missing: the APK would be debug-signed and phones could not update to it."; exit 1; }
if gh release view "$tag" >/dev/null 2>&1; then echo "$tag is already released. Raise versionName (and versionCode) in app/build.gradle.kts first."; exit 1; fi

jdk=/usr/lib/jvm/java-21-openjdk-amd64
[ -d "$jdk" ] && export JAVA_HOME=$jdk
./gradlew -q ${JAVA_HOME:+-Dorg.gradle.java.installations.paths=$JAVA_HOME} testDebugUnitTest assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
out=../dist/SolarMonitor-$ver.apk
mkdir -p ../dist
cp "$apk" "$out.tmp" && mv "$out.tmp" "$out"
gh release create "$tag" "$out" --title "Solar Monitor $ver" --notes "$notes"
echo "Released $tag. Phones on an older version will see the update the next time the app opens."
