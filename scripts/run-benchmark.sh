#!/usr/bin/env bash
# Skill-benchmark cell runner (POSIX / bash).
#
# Given a generated project directory and cell metadata, runs `mvn verify`
# and emits a row template the benchmark recorder pastes into the
# results-report's "Per-run data (raw)" table.
#
# The agent-invocation half (driving Claude / GPT / Gemini / Cursor on
# the locked prompt) is intentionally manual — each agent has its own
# UI that doesn't script cleanly. This script handles the deterministic
# part: build + capture + format.
#
# Usage:
#   scripts/run-benchmark.sh \
#       --project-dir ~/work/run1 \
#       --cell "tiko+skill, specified" \
#       --prompt specified \
#       --agent "claude-opus-4-7[1m] (SKILL.md auto-loaded)" \
#       --run-id 1 \
#       --transcript https://gist.github.com/... \
#       --source https://github.com/.../tree/run1
#
# Optional:
#   --tokens-in N / --tokens-out N (if the agent UI reports them)
#   --correction-turns N

set -euo pipefail

project_dir=""
cell=""
prompt=""
agent=""
run_id=""
transcript="(paste link)"
source_link="(paste link)"
tokens_in="?"
tokens_out="?"
correction_turns=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --project-dir)      project_dir="$2"; shift 2 ;;
        --cell)             cell="$2"; shift 2 ;;
        --prompt)           prompt="$2"; shift 2 ;;
        --agent)            agent="$2"; shift 2 ;;
        --run-id)           run_id="$2"; shift 2 ;;
        --transcript)       transcript="$2"; shift 2 ;;
        --source)           source_link="$2"; shift 2 ;;
        --tokens-in)        tokens_in="$2"; shift 2 ;;
        --tokens-out)       tokens_out="$2"; shift 2 ;;
        --correction-turns) correction_turns="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

if [[ -z "$project_dir" || -z "$cell" || -z "$prompt" || -z "$agent" || -z "$run_id" ]]; then
    echo "usage: $0 --project-dir DIR --cell NAME --prompt lean|specified --agent NAME --run-id N [opts]" >&2
    exit 2
fi
if [[ ! -d "$project_dir" ]]; then
    echo "project-dir not found: $project_dir" >&2
    exit 2
fi
if [[ "$prompt" != "lean" && "$prompt" != "specified" ]]; then
    echo "--prompt must be 'lean' or 'specified'" >&2
    exit 2
fi

# Resolve the mvn binary. Default to whatever's on PATH; allow override
# via the MVN environment variable for machines where mvn lives in a
# fixed install path not on PATH.
mvn_bin="${MVN:-mvn}"

log_file="$project_dir/benchmark-mvn.log"
echo "[harness] running '$mvn_bin verify' in $project_dir (log: $log_file)"

start_epoch=$(date +%s)
exit_code=0
( cd "$project_dir" && "$mvn_bin" verify ) > "$log_file" 2>&1 || exit_code=$?
end_epoch=$(date +%s)
wall_seconds=$(( end_epoch - start_epoch ))

if [[ $exit_code -eq 0 ]]; then
    first_build_pass="yes"
else
    first_build_pass="no"
fi

rubric_vector="_ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _"

echo ""
echo "===== ROW TEMPLATE (paste into results md) ====="
echo ""
echo "| $cell | $prompt | $agent | $run_id | $tokens_in | $tokens_out | $wall_seconds | $first_build_pass | $correction_turns | $rubric_vector | _ | $transcript | $source_link |"
echo ""
echo "===== END ROW ====="
echo ""
echo "mvn exit code: $exit_code"
echo "log: $log_file"
