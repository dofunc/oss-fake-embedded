#!/bin/sh
# Stands in for oss-go-fake: reports ready, then ignores SIGTERM so only a forced kill ends it.
# The ignored signal survives exec, so the sleep itself ignores TERM and dies on KILL without
# leaving an orphan. Writes its pid next to the data directory for the test.
trap '' TERM
while [ $# -gt 0 ]; do
  if [ "$1" = "--data-dir" ]; then echo $$ > "$2.pid"; fi
  shift
done
echo "oss-go-fake ready on 127.0.0.1:0"
exec sleep 600
