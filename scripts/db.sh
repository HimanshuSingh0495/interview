#!/usr/bin/env bash
# Query the local H2 database (works while the app is running).
#   ./scripts/db.sh                          -> show all tables
#   ./scripts/db.sh "SELECT * FROM note"     -> run a query
#   ./scripts/db.sh -i                       -> interactive sql> prompt (type 'quit' to exit)
cd "$(dirname "$0")/.."
H2_JAR=$(ls ~/.m2/repository/com/h2database/h2/*/h2-*.jar 2>/dev/null | grep -v sources | sort -V | tail -1)
[ -z "$H2_JAR" ] && { echo "H2 jar not found; run 'mvn test' once to download it."; exit 1; }
URL="jdbc:h2:./data/appdb;AUTO_SERVER=TRUE;IFEXISTS=TRUE"
if [ "$1" = "-i" ]; then
  exec java -cp "$H2_JAR" org.h2.tools.Shell -url "$URL" -user sa
fi
SQL="${1:-SELECT table_name FROM information_schema.tables WHERE table_schema = 'PUBLIC'}"
exec java -cp "$H2_JAR" org.h2.tools.Shell -url "$URL" -user sa -sql "$SQL"
