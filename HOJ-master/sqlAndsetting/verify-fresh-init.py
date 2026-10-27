"""Verify first initialization with Compose SQL mounts in disposable MySQL.

Python 3.9+ and the existing local Compose MySQL image are sufficient. No live
database connection, published port, persistent volume, or extra pip package.
One-off ALTER migrations are checked via the fresh schema, never replayed.
"""
import argparse
import difflib
import hashlib
import json
import re
import secrets
import subprocess
import time
from pathlib import Path


REPEATABLE = [
    'objective.sql', 'paper.sql', 'algorithm-topics.sql', 'ai-assistant.sql',
    'external-learning.sql', 'remote-sources.sql', 'atcoder-learning.sql',
    'nowcoder-learning.sql', 'remote-credentials.sql', 'personal-remote-tasks.sql',
    'personal-remote-dispatch.sql', 'remote-statement-refresh.sql', 'nowcoder-remote.sql',
    'nowcoder-sources.sql', 'nowcoder-context.sql', 'nowcoder-practice.sql', 'virtual-contests.sql',
    'mixed-virtual-contest.sql', 'knowledge-base.sql', 'knowledge-ocr.sql', 'knowledge-video.sql',
    'learning-training-plan.sql', 'knowledge-pdf-capacity.sql',
]
ONE_OFF = ['objective-standalone.sql', 'paper-judge.sql', 'mixed-contest.sql', 'site-rating.sql']


def run(*args, input=None, timeout=45):
    return subprocess.run(args, input=input, text=True, encoding='utf-8',
                          capture_output=True, timeout=timeout, check=True).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='A new evidence directory; existing reports are never replaced')
    args = parser.parse_args()
    out = args.output.resolve()
    if (out / 'fresh-init.json').exists():
        parser.error('Use a new output directory to preserve the previous report')
    out.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parents[1]
    nonce = secrets.token_hex(6)
    name = 'hoj-fresh-init-' + nonce
    report = {'validated': False, 'cleaned': False, 'liveDatabaseAccessed': False,
              'network': 'none', 'publishedPorts': False, 'persistentVolumes': False,
              'container': name, 'mounts': [], 'phase': 'compose-config', 'replayed': []}
    hashes = {}

    def mysql(query):
        return run('docker', 'exec', '-i', name, 'sh', '-c',
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names --raw --database=hoj', input=query)

    def snapshot():
        columns = mysql("SELECT TABLE_NAME,COLUMN_NAME,ORDINAL_POSITION,COLUMN_TYPE,IS_NULLABLE,COALESCE(COLUMN_DEFAULT,'<NULL>'),EXTRA,COALESCE(COLLATION_NAME,'') FROM information_schema.columns WHERE TABLE_SCHEMA='hoj' ORDER BY TABLE_NAME,ORDINAL_POSITION;")
        indexes = mysql("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COALESCE(COLUMN_NAME,''),COALESCE(SUB_PART,0),INDEX_TYPE FROM information_schema.statistics WHERE TABLE_SCHEMA='hoj' ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX;")
        keys = mysql("SELECT TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,ORDINAL_POSITION,COALESCE(REFERENCED_TABLE_NAME,''),COALESCE(REFERENCED_COLUMN_NAME,'') FROM information_schema.key_column_usage WHERE TABLE_SCHEMA='hoj' ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION;")
        return '\n'.join(['COLUMNS', columns, 'INDEXES', indexes, 'KEYS', keys])

    def logs():
        result = subprocess.run(['docker', 'logs', name], text=True, encoding='utf-8', capture_output=True, timeout=15, check=True)
        return result.stdout + '\n' + result.stderr

    try:
        # Capture config in memory; never print or archive rendered credentials.
        config = json.loads(run('docker', 'compose', '-f', str(root / 'compose.yaml'), 'config', '--format', 'json'))['services']['hoj-mysql']
        command = ['docker', 'run', '-d', '--pull', 'never', '--name', name,
                   '--label', 'hoj.fresh-init=' + nonce, '--network', 'none', '--memory', '2g',
                   '--tmpfs', '/var/lib/mysql:rw,nosuid,nodev,size=1536m',
                   '--env', 'MYSQL_ROOT_PASSWORD=' + secrets.token_urlsafe(24)]
        for field in ('MYSQL_ROOT_HOST', 'TZ'):
            if field in config.get('environment', {}):
                command += ['--env', field + '=' + config['environment'][field]]
        for volume in config['volumes']:
            if volume['type'] != 'bind':
                continue
            source = Path(volume['source']).resolve()
            if not source.is_relative_to(root / 'sqlAndsetting') or not volume.get('read_only'):
                raise ValueError('Only read-only SQL source mounts are supported')
            target = volume['target']
            command += ['--mount', 'type=bind,source=' + source.as_posix() + ',target=' + target + ',readonly']
            relative = source.relative_to(root).as_posix()
            report['mounts'].append({'file': relative, 'target': target})
            hashes[relative] = hashlib.sha256(source.read_bytes()).hexdigest()
        targets = {mount['target'] for mount in report['mounts']}
        if not {'/docker-entrypoint-initdb.d/01-hoj.sql', '/docker-entrypoint-initdb.d/02-nacos.sql'} <= targets:
            raise ValueError('The actual Compose initialization mounts are missing')
        command += [config['image'], *config.get('command', [])]
        run(*command)
        report['phase'] = 'initialization'
        print('Started isolated MySQL with actual Compose SQL mounts; no ports or live volumes.', flush=True)
        for attempt in range(180):
            state = json.loads(run('docker', 'inspect', name))[0]
            text = logs()
            if not state['State']['Running']:
                raise RuntimeError('Disposable MySQL exited during initialization')
            if 'MySQL init process done. Ready for start up.' in text:
                try:
                    if mysql('SELECT 1;') == '1':
                        break
                except subprocess.CalledProcessError:
                    pass
            time.sleep(1)
        else:
            raise RuntimeError('Disposable initialization did not finish')
        if state['HostConfig']['PortBindings'] or any(mount['Type'] == 'volume' for mount in state['Mounts']):
            raise RuntimeError('Unexpected port or persistent volume')
        if re.search(r'(^|\n)ERROR\s|\[ERROR\]', text):
            raise RuntimeError('Initialization log contains SQL/server errors')
        report['initializationLogVerified'] = True
        report['isolation'] = mysql('SELECT @@transaction_isolation;')
        if report['isolation'] != 'REPEATABLE-READ':
            raise RuntimeError('Unexpected MySQL isolation level')
        report['phase'] = 'fresh-schema'
        tables = set(mysql("SELECT table_name FROM information_schema.tables WHERE table_schema='hoj';").splitlines())
        required = set()
        for filename in REPEATABLE + ONE_OFF:
            source = root / 'sqlAndsetting' / filename
            required.update(re.findall(r'CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?([a-zA-Z0-9_]+)', source.read_text(encoding='utf-8'), re.I))
            hashes['sqlAndsetting/' + filename] = hashlib.sha256(source.read_bytes()).hexdigest()
        report['missingTables'] = sorted(required - tables)
        if report['missingTables']:
            raise RuntimeError('Fresh initialization lacks feature tables')
        report['tables'], report['requiredFeatureTables'] = len(tables), len(required)
        for table, column in [('problem', 'rating'), ('contest', 'rated'), ('judge', 'paper_run_id'),
                              ('objective_question', 'practice_enabled'), ('objective_run', 'question_id'),
                              ('paper_run', 'contest_id'), ('paper_run', 'virtual_run_id')]:
            if mysql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='hoj' AND table_name='" + table + "' AND column_name='" + column + "';") != '1':
                raise RuntimeError('Missing one-off migration result: ' + table + '.' + column)
        report['linkedTopics'] = int(mysql('SELECT COUNT(*) FROM algorithm_topic a JOIN tag t ON t.id=a.problem_tag_id JOIN training_category c ON c.id=a.training_category_id JOIN tag_classification g ON g.id=a.tag_classification_id;'))
        if mysql('SELECT COUNT(*) FROM algorithm_topic;') != '94' or report['linkedTopics'] != 94:
            raise RuntimeError('Predefined topic links are incomplete')
        if mysql("SELECT active_count FROM knowledge_video_quota WHERE owner='_all';") != '0':
            raise RuntimeError('Fresh video quota is not initialized')
        if mysql("SELECT COUNT(*) FROM user_info WHERE username='root' AND status=0;") != '1':
            raise RuntimeError('Initial root user is missing')
        report['nacosTables'] = int(mysql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='nacos';"))
        if not report['nacosTables']:
            raise RuntimeError('Nacos database is not initialized')
        mysql("INSERT INTO tag(name) VALUES('fresh-init-custom-" + nonce + "');INSERT INTO training_category(name) VALUES('fresh-init-custom-" + nonce + "');")
        before = snapshot()
        (out / 'schema-before.txt').write_text(before, encoding='utf-8')
        print('Fresh schema is present; checking repeatable migrations twice.', flush=True)
        for round in range(2):
            report['phase'] = 'migration-round-' + str(round + 1)
            for filename in REPEATABLE:
                mysql((root / 'sqlAndsetting' / filename).read_text(encoding='utf-8'))
                report['replayed'].append({'round': round + 1, 'file': filename})
            after = snapshot()
            (out / ('schema-round-' + str(round + 1) + '.txt')).write_text(after, encoding='utf-8')
            difference = '\n'.join(difflib.unified_diff(before.splitlines(), after.splitlines(), fromfile='fresh', tofile='after-migrations'))
            if difference:
                (out / ('schema-diff-' + str(round + 1) + '.txt')).write_text(difference, encoding='utf-8')
                report.setdefault('schemaChangedRounds', []).append(round + 1)
            for table in ('tag', 'training_category'):
                if mysql("SELECT COUNT(*) FROM " + table + " WHERE name='fresh-init-custom-" + nonce + "';") != '1':
                    raise RuntimeError('Custom vocabulary was not preserved')
            print('Completed repeatable migration round ' + str(round + 1), flush=True)
        report['oneOffMigrationsNotReplayed'] = ONE_OFF
        report['customTagsPreserved'] = True
        if report.get('schemaChangedRounds'):
            raise RuntimeError('Fresh Compose schema differs from supported additive migrations')
        report['validated'], report['phase'] = True, 'complete'
    except Exception as error:
        report['failure'] = {'type': type(error).__name__, 'message': str(error)[:300] if isinstance(error, (ValueError, RuntimeError)) else 'See retained phase and log; no credentials printed'}
    finally:
        try:
            found = subprocess.run(['docker', 'inspect', name], text=True, encoding='utf-8', capture_output=True, timeout=15)
            if found.returncode == 0:
                record = json.loads(found.stdout)[0]
                if record['Name'] != '/' + name or record['Config']['Labels'].get('hoj.fresh-init') != nonce or not name.startswith('hoj-fresh-init-'):
                    raise RuntimeError('Cleanup identity did not match')
                (out / 'mysql-initialization.log').write_text(logs(), encoding='utf-8')
                run('docker', 'rm', '-f', name)
            elif 'No such object' not in found.stderr and 'No such container' not in found.stderr:
                raise RuntimeError('Unable to confirm disposable container state')
            report['cleaned'] = not run('docker', 'ps', '-a', '--filter', 'name=^/' + name + '$', '--format', '{{.Names}}')
        except Exception as error:
            report['cleanupFailure'] = type(error).__name__
        hashes['compose.yaml'] = hashlib.sha256((root / 'compose.yaml').read_bytes()).hexdigest()
        hashes['sqlAndsetting/verify-fresh-init.py'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        (out / 'source-hashes.json').write_text(json.dumps(hashes, indent=2), encoding='utf-8')
        (out / 'fresh-init.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({key: report.get(key) for key in ('validated', 'cleaned', 'phase', 'tables', 'requiredFeatureTables', 'linkedTopics', 'isolation', 'schemaChangedRounds', 'failure', 'cleanupFailure')}, ensure_ascii=False), flush=True)
    return 0 if report['validated'] and report['cleaned'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
