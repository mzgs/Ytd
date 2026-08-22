#!/usr/bin/env bash

set -Eeuo pipefail

REMOTE="${1:-origin}"
JITPACK_GROUP="${JITPACK_GROUP:-com.github.mzgs}"
JITPACK_ARTIFACT="${JITPACK_ARTIFACT:-Ytd}"
JITPACK_BASE_URL="${JITPACK_BASE_URL:-https://jitpack.io}"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "Error: release.sh must be run inside a Git repository." >&2
    exit 1
fi

if ! git remote get-url "${REMOTE}" >/dev/null 2>&1; then
    echo "Error: Git remote '${REMOTE}' does not exist." >&2
    exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "Error: curl is required to trigger the JitPack build." >&2
    exit 1
fi

latest_tag=""
latest_major=-1
latest_minor=-1

while IFS= read -r ref; do
    tag="${ref#refs/tags/}"

    if [[ "${tag}" =~ ^([0-9]+)\.([0-9]+)$ ]]; then
        major=$((10#${BASH_REMATCH[1]}))
        minor=$((10#${BASH_REMATCH[2]}))

        if ((major > latest_major || (major == latest_major && minor > latest_minor))); then
            latest_tag="${tag}"
            latest_major=${major}
            latest_minor=${minor}
        fi
    fi
done < <(git ls-remote --tags --refs "${REMOTE}" | awk '{print $2}')

if [[ -z "${latest_tag}" ]]; then
    echo "Error: no numeric major.minor tags were found on '${REMOTE}'." >&2
    exit 1
fi

next_tag="${latest_major}.$((latest_minor + 1))"

if git rev-parse --verify --quiet "refs/tags/${next_tag}" >/dev/null; then
    echo "Error: local tag '${next_tag}' already exists." >&2
    exit 1
fi

echo "Creating release tag ${next_tag} from ${latest_tag} at $(git rev-parse --short HEAD)"
git tag --annotate "${next_tag}" --message "Release ${next_tag}"
git push "${REMOTE}" "refs/tags/${next_tag}"

group_path="${JITPACK_GROUP//./\/}"
artifact_url="${JITPACK_BASE_URL}/${group_path}/${JITPACK_ARTIFACT}/${next_tag}/${JITPACK_ARTIFACT}-${next_tag}.pom"
build_log_url="${JITPACK_BASE_URL}/${group_path}/${JITPACK_ARTIFACT}/${next_tag}/build.log"

echo "Triggering JitPack build for ${JITPACK_GROUP}:${JITPACK_ARTIFACT}:${next_tag}"
if ! curl \
    --fail \
    --location \
    --silent \
    --show-error \
    --retry 5 \
    --retry-all-errors \
    --retry-delay 5 \
    --connect-timeout 30 \
    --max-time 1800 \
    --output /dev/null \
    "${artifact_url}"; then
    echo "Error: JitPack failed to build ${next_tag}." >&2
    echo "Build log: ${build_log_url}" >&2
    exit 1
fi

echo "Released ${next_tag} to ${REMOTE}; JitPack build succeeded."
echo "Artifact: ${artifact_url}"
