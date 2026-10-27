"""Regenerate the versioned SQL catalogue from the authoritative algorithm-topics.json."""
from pathlib import Path
import json

root = Path(__file__).resolve().parents[1]
resource = root / "hoj-springboot/DataBackup/src/main/resources/algorithm-topics.json"
rows = json.loads(resource.read_text(encoding="utf-8"))
migration = root / "sqlAndsetting/algorithm-topics.sql"
sql = migration.read_text(encoding="utf-8")
start = sql.index("INSERT INTO algorithm_topic(code,name,group_code,group_name,sort_order) VALUES")
end = sql.index("ON DUPLICATE KEY UPDATE", start)
def quote(value):
    return "'" + str(value).replace("'", "''") + "'"
values = ",\n".join("(" + ",".join([quote(row["code"]), quote(row["name"]), quote(row["groupCode"]), quote(row["groupName"]), str(row["sortOrder"])]) + ")" for row in rows)
sql = sql[:start] + "INSERT INTO algorithm_topic(code,name,group_code,group_name,sort_order) VALUES\n" + values + "\n" + sql[end:]
migration.write_text(sql, encoding="utf-8")
init = root / "sqlAndsetting/hoj.sql"
marker = "-- BEGIN GENERATED ALGORITHM TOPICS (scripts/generate-algorithm-topics.py)"
end_marker = "-- END GENERATED ALGORITHM TOPICS"
original, _, generated = init.read_text(encoding="utf-8").partition(marker)
_, found, suffix = generated.partition(end_marker)
init.write_text(original.rstrip() + "\n\n" + marker + "\n" + sql.rstrip() + "\n" + end_marker + (suffix if found else "\n"), encoding="utf-8")
print(f"Generated {len(rows)} topics for migration and fresh database initialization.")
