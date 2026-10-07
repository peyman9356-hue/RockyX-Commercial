#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:?project root required}"
REPO_ROOT="${2:-$(pwd)}"
BACKEND="$ROOT/backend"

mkdir -p "$BACKEND/src/main/kotlin/com/rockyx/backend/training"
mkdir -p "$BACKEND/src/main/resources/db/migration"
mkdir -p "$BACKEND/src/test/kotlin/com/rockyx/backend"

cp "$REPO_ROOT/backend/src/main/kotlin/com/rockyx/backend/training/TrainingSyncTransport.kt" \
   "$BACKEND/src/main/kotlin/com/rockyx/backend/training/TrainingSyncTransport.kt"
cp "$REPO_ROOT/backend/src/main/kotlin/com/rockyx/backend/training/PostgresTrainingSyncTransportRepository.kt" \
   "$BACKEND/src/main/kotlin/com/rockyx/backend/training/PostgresTrainingSyncTransportRepository.kt"
cp "$REPO_ROOT/backend/src/main/resources/db/migration/V12__training_sync_transport.sql" \
   "$BACKEND/src/main/resources/db/migration/V12__training_sync_transport.sql"
cp "$REPO_ROOT/backend/src/main/kotlin/com/rockyx/backend/training/TrainingSyncHttpRoutes.kt" \
   "$BACKEND/src/main/kotlin/com/rockyx/backend/training/TrainingSyncHttpRoutes.kt"
cp "$REPO_ROOT/backend/src/test/kotlin/com/rockyx/backend/TrainingSyncHttpRouteTest.kt" \
   "$BACKEND/src/test/kotlin/com/rockyx/backend/TrainingSyncHttpRouteTest.kt"

python3 - "$BACKEND/src/main/kotlin/com/rockyx/backend/api/Routes.kt" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
if 'import com.rockyx.backend.training.TrainingSyncTransportRepository' not in s:
    s = s.replace(
        'import com.rockyx.backend.ai.*\n',
        'import com.rockyx.backend.ai.*\n'
        'import com.rockyx.backend.training.TrainingSyncTransportRepository\n'
        'import com.rockyx.backend.training.registerTrainingSyncHttpRoute\n'
    )
needle = '    ecosystem: EcosystemRepository = InMemoryEcosystemRepository()\n) {'
repl = '    ecosystem: EcosystemRepository = InMemoryEcosystemRepository(),\n'
repl += '    trainingSyncTransport: TrainingSyncTransportRepository? = null\n) {'
if needle not in s:
    raise SystemExit('Routes signature anchor missing')
s = s.replace(needle, repl, 1)
anchor = '''        get("/internal/metrics") {
            // Internal diagnostics are deliberately unavailable through the public API surface.
            call.respond(HttpStatusCode.NotFound)
        }
'''
insert = anchor + '\n        trainingSyncTransport?.let { registerTrainingSyncHttpRoute(verifier, it, idempotency) }\n'
if anchor not in s:
    raise SystemExit('Routes insertion anchor missing')
s = s.replace(anchor, insert, 1)
p.write_text(s)
PY

python3 - "$BACKEND/src/main/kotlin/com/rockyx/backend/Application.kt" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
needle = '        val postgresSessions = PostgresAuthSessionRepository(ds)\n'
repl = needle + '        val trainingSyncTransport = com.rockyx.backend.training.PostgresTrainingSyncTransportRepository(ds)\n'
if needle not in s:
    raise SystemExit('Application transport anchor missing')
s = s.replace(needle, repl, 1)
old = 'PostgresRtdnMessageRepository(ds), postgresSessions, sessionConfig?.let { SessionManager(it, postgresSessions) }, externalVerifier, googleIdentityVerifier, PostgresAccountProfileRepository(ds), PostgresEcosystemRepository(ds))'
new = 'PostgresRtdnMessageRepository(ds), postgresSessions, sessionConfig?.let { SessionManager(it, postgresSessions) }, externalVerifier, googleIdentityVerifier, PostgresAccountProfileRepository(ds), PostgresEcosystemRepository(ds), trainingSyncTransport)'
if old not in s:
    raise SystemExit('Application route call anchor missing')
s = s.replace(old, new, 1)
p.write_text(s)
PY
