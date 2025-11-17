#!/usr/bin/env bash
ROLE=$1
SPEC_ID=$2
if [ -z "$ROLE" ]; then
  ROLE=""
fi
if [ -z "$SPEC_ID" ]; then
  SPEC_FORM="nil"
else
  SPEC_FORM="\"$SPEC_ID\""
fi
if [ -z "$ROLE" ]; then
  clojure -M -e "(require 'agent.core)" -r
else
  clojure -M -e "(require 'agent.core) (agent.core/boot! \"$ROLE\" $SPEC_FORM)" -r
fi
