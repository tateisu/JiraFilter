#!/bin/bash --
set -eux

./gradlew shadowJar

# 生成された最新のjarファイルのpath
# shellcheck disable=SC2012
buildJar=$(ls -1t build/libs/J*.jar|head -n 1|sed -e "s/[\r\n]\+//g")

if [ -z "$buildJar" ]; then
  echo "ERROR: missing jar file in build/libs."
  exit 1
fi

cp -rap "$buildJar" ./JiraFilter.jar
