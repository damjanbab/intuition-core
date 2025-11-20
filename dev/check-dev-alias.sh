#!/usr/bin/env bash
set -euo pipefail

echo "[alias-check] verifying :dev alias loads dev.list-missions per SYSTEM_SPEC §5.3"
clojure -M:dev -e "(require 'dev.list-missions)"
echo "[alias-check] dev.list-missions required successfully"
