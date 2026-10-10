#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Runs every Ruby example on the website through the Ruby client (HTTP
# intercepted, no server) and compares what it sends with the REST API tab.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i ruby:3.3 \
  -w /build/mockserver-client-ruby \
  --cache bundler \
  -- bash -c "bundle install && bundle exec ruby spec/website_examples/check_website_examples.rb"
