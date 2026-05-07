#!/usr/bin/env bash
#
# Interactive release driver for CheeseBTCWidget.
#
# Run from the project root:
#     chmod +x release.sh    # one-time
#     ./release.sh
#
# Walks through:
#   1) git status -> paste file paths to stage (or "." for all)
#   2) git commit (you supply the message; skipped if nothing is staged)
#   3) read versionName from app/build.gradle.kts -> tag + push origin
#   4) pause: build a signed release APK in Android Studio
#   5) cp app/release/app-release.apk -> CheeseBTCWidget-v<version>.apk
#   6) pause: create a GitHub release and attach the APK
#   7) run `zsp publish --skip-certificate-linking`
#
# Bails out on any error; the trap surfaces the line number so a copy-
# paste failure is easy to inspect.

set -euo pipefail

# ---- ANSI helpers ----------------------------------------------------------
# Guarded with `-t 1` so a redirected run (e.g. `./release.sh | tee log`)
# doesn't write escape sequences into the file.
if [[ -t 1 ]]; then
  BOLD=$'\e[1m'
  RED=$'\e[31m'
  GREEN=$'\e[32m'
  YELLOW=$'\e[33m'
  BLUE=$'\e[34m'
  RESET=$'\e[0m'
else
  BOLD=""; RED=""; GREEN=""; YELLOW=""; BLUE=""; RESET=""
fi

step() { printf "\n%s== %s ==%s\n" "${BOLD}${BLUE}" "$*" "${RESET}"; }
note() { printf "%s%s%s\n" "${YELLOW}" "$*" "${RESET}"; }
ok()   { printf "%s%s%s\n" "${GREEN}"  "$*" "${RESET}"; }
fail() { printf "%s%s%s\n" "${RED}"    "$*" "${RESET}" >&2; }

trap 'fail "release.sh aborted (line $LINENO)"; exit 1' ERR

confirm() {
  local prompt="${1:-Continue?}"
  local a
  read -r -p "${BOLD}${prompt} [y/N]:${RESET} " a
  [[ "${a,,}" == y* ]]
}

pause() {
  local msg="${1:-Press <enter> when done...}"
  read -r -p "${BOLD}${YELLOW}${msg}${RESET} "
}

# ---- Sanity ----------------------------------------------------------------
if [[ ! -f "app/build.gradle.kts" ]]; then
  fail "Run this from the CheeseWidget project root (no app/build.gradle.kts here)."
  exit 1
fi
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  fail "This directory isn't a git working tree."
  exit 1
fi

# ---- 1) git add ------------------------------------------------------------
step "1/7  git status"
git status --short || true
echo
note "Paste paths to stage, one per line. Empty line to stop."
note "Tip: a single '.' on its own line stages everything (git add -A)."

files=()
while IFS= read -r line; do
  [[ -z "$line" ]] && break
  files+=("$line")
done

if (( ${#files[@]} == 0 )); then
  note "No paths entered -- skipping git add."
elif [[ ${#files[@]} -eq 1 && "${files[0]}" == "." ]]; then
  git add -A
  ok "Staged everything. Currently:"
  git status --short
else
  git add -- "${files[@]}"
  ok "Staged. Currently:"
  git status --short
fi

# ---- 2) git commit ---------------------------------------------------------
step "2/7  git commit"
if git diff --cached --quiet; then
  note "Nothing staged -- skipping commit. The next step will tag the existing HEAD."
else
  msg=""
  read -r -p "${BOLD}Commit message: ${RESET}" msg
  if [[ -z "$msg" ]]; then
    fail "Empty commit message -- aborting."
    exit 1
  fi
  git commit -m "$msg"
  ok "Committed: $(git log -1 --oneline)"
fi

# ---- 3) read versionName, tag, push ----------------------------------------
step "3/7  read versionName + tag + push"
version="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' app/build.gradle.kts \
  | head -n1 \
  | sed -E 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/')"

if [[ -z "$version" ]]; then
  fail "Couldn't extract versionName from app/build.gradle.kts."
  exit 1
fi

tag="v${version}"
note "Detected versionName = ${BOLD}${version}${RESET}${YELLOW}  ->  tag will be ${BOLD}${tag}${RESET}"

if git rev-parse --verify --quiet "refs/tags/${tag}" >/dev/null; then
  fail "Tag ${tag} already exists locally."
  fail "Bump versionName in app/build.gradle.kts first, then re-run."
  exit 1
fi

branch="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$branch" == "HEAD" ]]; then
  fail "HEAD is detached -- check out a branch before tagging."
  exit 1
fi

if ! confirm "Create annotated tag ${tag} on branch ${branch} and push to origin?"; then
  note "Skipped tagging and pushing. Stopping."
  exit 0
fi

git tag -a "$tag" -m "Release ${tag}"
# Push the branch by name (not HEAD) so this works even when no upstream
# is configured yet -- `git push origin HEAD` requires a tracked branch.
# `-u` sets the upstream on first push so future plain `git push` works.
git push -u origin "$branch"
git push origin "$tag"
ok "Pushed branch ${branch} and tag ${tag} to origin."

# ---- 4) build in Android Studio --------------------------------------------
step "4/7  Build the signed release APK in Android Studio"
note "  In Android Studio:"
note "    Build  ->  Generate Signed App Bundle / APK  ->  APK  ->  release"
note "  Output should land at:  app/release/app-release.apk"
pause "Press <enter> once Android Studio reports BUILD SUCCESSFUL..."

# ---- 5) copy + rename APK --------------------------------------------------
step "5/7  copy + rename APK"
src="app/release/app-release.apk"
dst="CheeseBTCWidget-${tag}.apk"

if [[ ! -f "$src" ]]; then
  fail "${src} not found. Did the release build succeed?"
  exit 1
fi

cp "$src" "$dst"
ok "Copied  ${src}  ->  ./${dst}"
ls -lh "$dst"

# ---- 6) GitHub release -----------------------------------------------------
step "6/7  Create the GitHub release"
note "  https://github.com/AbelLykens/org.cheeserobot.btcwidget/releases/new"
note "    Tag:    ${tag}"
note "    Title:  ${tag}"
note "    Notes:  paste the v${version} section from CHANGELOG.md"
note "    Attach: ${dst}"
pause "Press <enter> once the GitHub release is published..."

# ---- 7) zsp publish --------------------------------------------------------
step "7/7  zsp publish"
note "Running: zsp publish --skip-certificate-linking"
zsp publish --skip-certificate-linking
ok "Done -- release ${tag} is out."
