#!/usr/bin/env bash
# Checks the tree that will be published for private information, and for leftover Korean.
#
# 🔑 **Why this script is short.** It used to scan the whole repository and carry eighteen lines of
#    "ignore this one" (a denylist). That shape leaks: a file nobody thought to exclude goes out.
#    Now it looks only at what has been *gathered* into this directory (an allowlist) ⇒
#    **anything not gathered was never going to be published, so it cannot leak.**
#
#   bash scripts/check-private-info.sh          # exits 1 if it finds anything
#   bash scripts/check-private-info.sh --files  # lists the files that will be published
#
# 🔴 **Only tracked files are judged.** local.properties and keystore.properties are gitignored, so
#    they are not published even though they sit in this directory - but that is only true if the
#    final assembly uses `git ls-files`. 🚫 Copying the directory with `rsync -a` takes the
#    connection password with it.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1          # the root of the publishable tree
HERE="${PWD##*/}"

# The tracked files that will be published (third-party code and this script are excluded)
public_files() {
  git ls-files . \
    | grep -vE '(^|/)(libjpeg-turbo)/' \
    | grep -vE '/com/tigervnc/' \
    | grep -v 'scripts/check-private-info.sh'
}

if [ "${1:-}" = "--files" ]; then
  public_files | sed "s|^|$HERE/|"
  echo; echo "$(public_files | wc -l) files (excluding third-party code)"
  exit 0
fi

# name | regex | why it is blocked
PATTERNS=(
  "private IP|192\.168\.[0-9]+\.[0-9]+|somebody's LAN address. For an example, use RFC 5737's 192.0.2.0/24"
  "signing key path|keys-doldam|where the upload key sits on the maintainer's machine"
  "private tooling|doldam-play|a path to private tooling outside this repository"
  "private git|ssh://git|a private server address"
  "device model|SM.A908N|the maintainer's actual phone"
  "device serial|RFCM801|a real device serial number"
  "home path|/home/[a-z]+|a path containing a real account name"
  "host nickname|(^|[^a-zA-Z])(hp|bill)([^a-zA-Z]|\$)|nicknames for the maintainer's machines"
  "session leftovers|phase[0-9]_env|a path from the experiment-log era, not the product structure"
)

total=0
mapfile -t FILES < <(public_files)
[ "${#FILES[@]}" -eq 0 ] && { echo "🔴 nothing would be published - check the path"; exit 1; }

for p in "${PATTERNS[@]}"; do
  name="${p%%|*}"; rest="${p#*|}"; re="${rest%%|*}"; why="${rest#*|}"
  hits=$(grep -nIE "$re" "${FILES[@]}" 2>/dev/null)
  n=$(printf '%s' "$hits" | grep -c . || true)
  if [ "$n" -gt 0 ]; then
    printf '\n🔴 %s - %d place(s)\n   why: %s\n' "$name" "$n" "$why"
    printf '%s\n' "$hits" | sed 's/^/     /'
    total=$((total + n))
  fi
done

# ── Korean outside res/values-ko/ ─────────────────────────────────────────────
# 🔑 Source and documentation here are English only, and res/values-ko/ is the one place Korean
#    belongs: it is the translation that keeps the app in Korean for the people who use it.
#    Anything else is a leftover from before the translation pass.
# ℹ️ One deliberate exception: VncKeyMapperTest uses a Korean syllable as test *data*, to check the
#    keysym rule for characters outside Latin-1. It is written as an escape so it does not match.
kr=$(printf '%s\n' "${FILES[@]}" \
  | grep -v 'res/values-ko/' \
  | xargs grep -nIP '[가-힣]' 2>/dev/null)
kn=$(printf '%s' "$kr" | grep -c . || true)
if [ "$kn" -gt 0 ]; then
  printf '\n🔴 Korean text - %d line(s)\n   why: this repository is English-only outside res/values-ko/\n' "$kn"
  printf '%s\n' "$kr" | sed 's/^/     /'
  total=$((total + kn))
fi

echo
if [ "$total" -eq 0 ]; then
  echo "✅ clean - $(public_files | wc -l) files, no exclusions needed"
  exit 0
fi
echo "🔴 $total problem(s) remain."
exit 1
