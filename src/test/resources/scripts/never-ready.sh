#!/bin/sh
# Stands in for oss-go-fake but never prints the ready line. Writes its pid next to the data
# directory so the test can check the process is gone afterwards; exec keeps that pid.
while [ $# -gt 0 ]; do
  if [ "$1" = "--data-dir" ]; then echo $$ > "$2.pid"; fi
  shift
done
exec sleep 600
