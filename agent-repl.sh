#!/usr/bin/env bash
ROLE="$1"
SPEC_ID="$2"

if [ -z "$ROLE" ]; then
  ROLE_FORM="nil"
else
  ROLE_FORM="\"$ROLE\""
fi

if [ -z "$SPEC_ID" ]; then
  SPEC_FORM="nil"
else
  SPEC_FORM="\"$SPEC_ID\""
fi

clojure -M -e "(require 'agent.core) (agent.core/boot! $ROLE_FORM $SPEC_FORM)" -r
