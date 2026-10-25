#!/usr/bin/env python3
"""Render both database modes and check deployment contracts without a Kubernetes connection."""
import json
import subprocess
import tempfile
import yaml

chart = 'helm/teamcode'


def server(documents):
    """The server Deployment, by name: the MCP host is a Deployment too."""
    return next(doc for doc in documents if doc['kind'] == 'Deployment' and doc['metadata']['name'] == 'test-teamcode')


for external in (False, True):
    flags = ['--set', 'publicUrl=https://teamcode.example.org']
    if external:
        flags += ['--set', 'mysql.enabled=false', '--set', 'mysql.jdbcUrl=jdbc:mysql://db.example:3306/teamcode',
                  '--set', 'autoscaling.enabled=true', '--set', 'ingress.enabled=true',
                  '--set', 'ingress.hosts[0].host=teamcode.example.org', '--set', 'ingress.hosts[0].paths[0].path=/',
                  '--set', 'ingress.hosts[0].paths[0].pathType=Prefix']
    subprocess.run(['helm', 'lint', chart, '--strict', *flags], check=True)
    rendered = subprocess.run(['helm', 'template', 'test', chart, *flags], check=True, capture_output=True, text=True).stdout
    documents = [doc for doc in yaml.safe_load_all(rendered) if doc]
    deployment = server(documents)
    pod = deployment['spec']['template']['spec']
    container = pod['containers'][0]
    assert container['image'] == 'nathanfallet/teamcode:latest'
    assert container['imagePullPolicy'] == 'Always'
    env = {item['name']: item for item in container['env']}
    assert env['TEAMCODE_HOST']['value'] == '0.0.0.0'
    assert env['TEAMCODE_DATABASE_PASSWORD']['valueFrom']['secretKeyRef']['name'] == 'teamcode-secrets'
    assert env['TEAMCODE_ENCRYPTION_KEY']['valueFrom']['secretKeyRef']['key'] == 'encryption-key'
    assert container['readinessProbe']['httpGet']['path'] == '/ready'
    assert container['livenessProbe']['httpGet']['path'] == '/health'
    assert pod['terminationGracePeriodSeconds'] >= 650
    assert pod['automountServiceAccountToken'] is False
    # The chart generates one Secret only, the MCP host token; every other secret is the operator's.
    assert [doc['metadata']['name'] for doc in documents if doc['kind'] == 'Secret'] == ['test-teamcode-mcp']
    assert any(doc['kind'] == 'StatefulSet' for doc in documents) != external
    assert any(doc['kind'] == 'HorizontalPodAutoscaler' for doc in documents) == external
    assert ('replicas' not in deployment['spec']) == external
    # Session pods' network policy, applied by the server: on by default, and sessions keep full internet access
    # (no lockdown, nothing blocked beyond the built-in cluster-internal and metadata ranges, no extra rule).
    # SessionNetworkPolicyTest.theChartDefaultsKeepFullInternetAccess builds the policy from exactly these values.
    assert env['TEAMCODE_CLOUD_SESSION_NETWORK_POLICY']['value'] == 'true'
    # Sign-in throttling trusts no proxy unless the operator lists one.
    assert env['TEAMCODE_TRUSTED_PROXIES']['value'] == ''
    # Webhooks reach public addresses only unless the operator opts in (audit L1).
    assert env['TEAMCODE_WEBHOOK_PRIVATE_TARGETS']['value'] == 'false'
    assert env['TEAMCODE_CLOUD_SESSION_EGRESS_LOCKDOWN']['value'] == 'false'
    assert env['TEAMCODE_CLOUD_SESSION_EGRESS_BLOCKED_CIDRS']['value'] == ''
    assert json.loads(env['TEAMCODE_CLOUD_SESSION_EGRESS_EXTRA']['value']) == []
    policies = [doc for doc in documents if doc['kind'] == 'NetworkPolicy' and doc['metadata']['name'] != 'test-teamcode-mcp']
    if external:
        assert not policies, 'No MySQL policy without the bundled MySQL'
    else:
        # The bundled MySQL accepts only this release's server pods, on 3306.
        mysql = next(doc for doc in documents if doc['kind'] == 'StatefulSet')
        policy = policies[0]
        assert len(policies) == 1 and policy['spec']['policyTypes'] == ['Ingress']
        assert policy['spec']['podSelector']['matchLabels'].items() <= mysql['spec']['template']['metadata']['labels'].items()
        (rule,) = policy['spec']['ingress']
        assert rule['ports'] == [{'protocol': 'TCP', 'port': 3306}]
        (peer,) = rule['from']
        assert set(peer) == {'podSelector'}
        assert peer['podSelector']['matchLabels'].items() <= deployment['spec']['template']['metadata']['labels'].items()
        assert not peer['podSelector']['matchLabels'].items() <= mysql['spec']['template']['metadata']['labels'].items()
        # The bundled MySQL reads the chart's bounded memory settings, and a changed setting rolls its pod.
        config = next(doc for doc in documents if doc['kind'] == 'ConfigMap' and doc['metadata']['name'] == mysql['metadata']['name'])
        cnf = config['data']['teamcode.cnf']
        assert cnf.lstrip().startswith('[mysqld]') and 'temptable_max_ram' in cnf and 'innodb_buffer_pool_size' in cnf, cnf
        mysql_pod = mysql['spec']['template']
        assert 'checksum/mysql-config' in mysql_pod['metadata']['annotations']
        (mysql_container,) = mysql_pod['spec']['containers']
        mount = next(m for m in mysql_container['volumeMounts'] if m['name'] == 'config')
        assert mount['mountPath'] == '/etc/mysql/conf.d/teamcode.cnf' and mount['subPath'] == 'teamcode.cnf'
        assert mysql_container['resources']['limits']['memory'] == '2Gi'
    if external:
        ingress = next(doc for doc in documents if doc['kind'] == 'Ingress')
        assert ingress['metadata']['annotations']['nginx.ingress.kubernetes.io/proxy-buffering'] == 'off'
        # Artifact content relies on the application's Content-Security-Policy sandbox on every response: the ingress
        # must pass response headers through, never hide or rewrite them.
        annotations = ingress['metadata']['annotations']
        assert not any('snippet' in key or 'hide-headers' in key or 'custom-headers' in key for key in annotations), annotations
        assert 'content-security-policy' not in yaml.safe_dump(ingress).lower()
# The operator's session egress settings reach the server as written.
values = {'publicUrl': 'https://teamcode.example.org', 'mysql': {'networkPolicy': {'enabled': False}}, 'cloudSessions': {'networkPolicy': {
    'enabled': True, 'lockdown': True, 'blockedCidrs': ['203.0.113.0/24', '2001:db8::/32'],
    'extraEgress': [{'to': [{'namespaceSelector': {'matchLabels': {'kubernetes.io/metadata.name': 'ingress-nginx'}}}], 'ports': [{'protocol': 'TCP', 'port': 443}]}]}}}
with tempfile.NamedTemporaryFile('w', suffix='.yaml') as handle:
    yaml.safe_dump(values, handle)
    handle.flush()
    documents = [doc for doc in yaml.safe_load_all(subprocess.run(['helm', 'template', 'test', chart, '-f', handle.name], check=True, capture_output=True, text=True).stdout) if doc]
env = {item['name']: item['value'] for item in server(documents)['spec']['template']['spec']['containers'][0]['env'] if 'value' in item}
assert env['TEAMCODE_CLOUD_SESSION_EGRESS_LOCKDOWN'] == 'true'
assert env['TEAMCODE_CLOUD_SESSION_EGRESS_BLOCKED_CIDRS'] == '203.0.113.0/24,2001:db8::/32'
assert json.loads(env['TEAMCODE_CLOUD_SESSION_EGRESS_EXTRA']) == values['cloudSessions']['networkPolicy']['extraEgress']
assert [doc['metadata']['name'] for doc in documents if doc['kind'] == 'NetworkPolicy'] == ['test-teamcode-mcp'], 'mysql.networkPolicy.enabled=false renders no MySQL policy'
# A null list (an empty `extraEgress:` key) still renders a JSON array, and `enabled: false` reaches the server as written.
rendered = subprocess.run(['helm', 'template', 'test', chart, '--set', 'publicUrl=https://teamcode.example.org', '--set', 'cloudSessions.networkPolicy.extraEgress=null',
                           '--set', 'cloudSessions.networkPolicy.blockedCidrs=null', '--set', 'cloudSessions.networkPolicy.enabled=false'], check=True, capture_output=True, text=True).stdout
env = {item['name']: item.get('value') for item in server([doc for doc in yaml.safe_load_all(rendered) if doc])['spec']['template']['spec']['containers'][0]['env']}
assert env['TEAMCODE_CLOUD_SESSION_EGRESS_EXTRA'] == '[]' and env['TEAMCODE_CLOUD_SESSION_EGRESS_BLOCKED_CIDRS'] == ''
assert env['TEAMCODE_CLOUD_SESSION_NETWORK_POLICY'] == 'false'
rendered = subprocess.run(['helm', 'template', 'test', chart, '--set', 'publicUrl=https://teamcode.example.org', '--set', 'trustedProxies={10.244.0.0/16,fd00::/8}'], check=True, capture_output=True, text=True).stdout
env = {item['name']: item.get('value') for item in server([doc for doc in yaml.safe_load_all(rendered) if doc])['spec']['template']['spec']['containers'][0]['env']}
assert env['TEAMCODE_TRUSTED_PROXIES'] == '10.244.0.0/16,fd00::/8'
rendered = subprocess.run(['helm', 'template', 'test', chart, '--set', 'publicUrl=https://teamcode.example.org', '--set', 'webhooks.allowPrivateTargets=true'], check=True, capture_output=True, text=True).stdout
env = {item['name']: item.get('value') for item in server([doc for doc in yaml.safe_load_all(rendered) if doc])['spec']['template']['spec']['containers'][0]['env']}
assert env['TEAMCODE_WEBHOOK_PRIVATE_TARGETS'] == 'true'
# A released chart deploys its own version (audit L8): helm package sets appVersion, which the image tag defaults to,
# and an exact version is not pulled again on every start.
with tempfile.TemporaryDirectory() as packaged:
    subprocess.run(['helm', 'package', chart, '--destination', packaged, '--version', '9.8.7', '--app-version', '9.8.7'], check=True, capture_output=True)
    rendered = subprocess.run(['helm', 'template', 'test', f'{packaged}/teamcode-9.8.7.tgz', '--set', 'publicUrl=https://teamcode.example.org'], check=True, capture_output=True, text=True).stdout
    container = server([doc for doc in yaml.safe_load_all(rendered) if doc])['spec']['template']['spec']['containers'][0]
    assert container['image'] == 'nathanfallet/teamcode:9.8.7' and container['imagePullPolicy'] == 'IfNotPresent', container['image']
    rendered = subprocess.run(['helm', 'template', 'test', f'{packaged}/teamcode-9.8.7.tgz', '--set', 'publicUrl=https://teamcode.example.org', '--set', 'image.tag=latest'], check=True, capture_output=True, text=True).stdout
    container = server([doc for doc in yaml.safe_load_all(rendered) if doc])['spec']['template']['spec']['containers'][0]
    assert container['image'] == 'nathanfallet/teamcode:latest' and container['imagePullPolicy'] == 'Always'
# The bundled MySQL's volume size: 30Gi for a new installation (lookup finds nothing outside a cluster), an explicit
# value as written, and an existing StatefulSet's size kept, since volumeClaimTemplates is immutable. lookup cannot be
# faked offline, so the helper is rendered from a copy of the chart with a probe template that passes it fixtures.
def claim_size(flags):
    rendered = subprocess.run(['helm', 'template', 'test', chart, '--set', 'publicUrl=https://teamcode.example.org', *flags],
                              check=True, capture_output=True, text=True).stdout
    mysql = next(doc for doc in yaml.safe_load_all(rendered) if doc and doc['kind'] == 'StatefulSet')
    return mysql['spec']['volumeClaimTemplates'][0]['spec']['resources']['requests']['storage']
assert claim_size([]) == '30Gi'
assert claim_size(['--set', 'mysql.persistence.size=50Gi']) == '50Gi'
with tempfile.TemporaryDirectory() as probe_dir:
    import shutil
    probe_chart = f'{probe_dir}/teamcode'
    shutil.copytree(chart, probe_chart)
    existing = '(dict "spec" (dict "volumeClaimTemplates" (list (dict "metadata" (dict "name" "data") "spec" (dict "resources" (dict "requests" (dict "storage" "10Gi")))))))'
    with open(f'{probe_chart}/templates/zz-size-probe.yaml', 'w') as probe:
        probe.write('apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: size-probe\ndata:\n'
                    f'  existing: {{{{ include "teamcode.mysql.storageSize" (dict "explicit" "" "existing" {existing}) | quote }}}}\n'
                    f'  explicit: {{{{ include "teamcode.mysql.storageSize" (dict "explicit" "50Gi" "existing" {existing}) | quote }}}}\n'
                    '  absent: {{ include "teamcode.mysql.storageSize" (dict "explicit" "" "existing" dict) | quote }}\n')
    rendered = subprocess.run(['helm', 'template', 'test', probe_chart, '--set', 'publicUrl=https://teamcode.example.org',
                               '--show-only', 'templates/zz-size-probe.yaml'], check=True, capture_output=True, text=True).stdout
    sizes = yaml.safe_load(rendered)['data']
    assert sizes == {'existing': '10Gi', 'explicit': '50Gi', 'absent': '30Gi'}, sizes
# Misconfigured installations must fail at render time.
# A Bitnami-style mysql.primary.resources would be silently ignored and leave the bundled MySQL at its default limit.
misplaced = subprocess.run(['helm', 'template', 'test', chart, '--set', 'publicUrl=https://teamcode.example.org',
                            '--set', 'mysql.primary.resources.limits.memory=4Gi'], capture_output=True, text=True)
assert misplaced.returncode != 0 and 'mysql.resources' in misplaced.stderr, misplaced.stderr
for flags in ([], ['--set', 'publicUrl=https://teamcode.example.org', '--set', 'mysql.enabled=false']):
    assert subprocess.run(['helm', 'template', 'test', chart, *flags], capture_output=True).returncode != 0
# The MCP stdio host (templates/mcp.yaml, docs/mcp.md): on by default, isolated from the database and the cluster API,
# reachable from the server pods only, and sharing one generated token with them.
import base64
import hashlib
import ipaddress
import re


def render(*flags, chart_path=chart):
    result = subprocess.run(['helm', 'template', 'test', chart_path, '--set', 'publicUrl=https://teamcode.example.org', *flags],
                            check=True, capture_output=True, text=True)
    return [doc for doc in yaml.safe_load_all(result.stdout) if doc]


def mcp_objects(documents):
    return {doc['kind']: doc for doc in documents if doc['metadata']['name'] == 'test-teamcode-mcp'}


def matches(selector, labels):
    return selector.items() <= labels.items()


documents = render()
objects = mcp_objects(documents)
assert set(objects) == {'ServiceAccount', 'Service', 'Deployment', 'Secret', 'NetworkPolicy'}, set(objects)
assert not any(doc['kind'] in ('Role', 'RoleBinding', 'ClusterRole', 'ClusterRoleBinding') for doc in documents)
host = objects['Deployment']
host_pod = host['spec']['template']
host_labels = host_pod['metadata']['labels']
spec = host_pod['spec']
(container,) = spec['containers']
assert host['spec']['replicas'] == 1 and host['spec']['strategy']['rollingUpdate'] == {'maxSurge': 1, 'maxUnavailable': 0}
assert matches(host['spec']['selector']['matchLabels'], host_labels)
assert container['image'] == 'nathanfallet/teamcode-mcp:latest' and container['imagePullPolicy'] == 'Always'
# Restricted pod security, no Kubernetes identity, nothing inherited from service links.
assert spec['automountServiceAccountToken'] is False and spec['enableServiceLinks'] is False
assert spec['serviceAccountName'] == 'test-teamcode-mcp' and objects['ServiceAccount']['automountServiceAccountToken'] is False
assert spec['securityContext']['runAsNonRoot'] is True and spec['securityContext']['runAsUser'] == 10002
assert spec['securityContext']['seccompProfile'] == {'type': 'RuntimeDefault'}
security = container['securityContext']
assert security['readOnlyRootFilesystem'] is True and security['allowPrivilegeEscalation'] is False
assert security['capabilities'] == {'drop': ['ALL']} and security['runAsUser'] == 10002 and security['seccompProfile'] == {'type': 'RuntimeDefault'}
mounts = {m['mountPath']: m['name'] for m in container['volumeMounts']}
assert mounts == {'/var/mcp': 'data', '/tmp': 'tmp'}
assert all('emptyDir' in v and v['emptyDir']['sizeLimit'] for v in spec['volumes'])
assert container['resources']['limits']['memory'] == '2Gi' and 'ephemeral-storage' in container['resources']['limits']
assert container['ports'] == [{'name': 'http', 'containerPort': 8090, 'protocol': 'TCP'}]
assert container['startupProbe']['httpGet']['path'] == '/health' and container['livenessProbe']['httpGet']['path'] == '/health'
assert container['readinessProbe']['httpGet']['path'] == '/ready'
host_env = {item['name']: item for item in container['env']}
assert int(spec['terminationGracePeriodSeconds']) > int(host_env['TEAMCODE_MCP_DRAIN_SECONDS']['value'])
assert host_env['TEAMCODE_MCP_PORT']['value'] == '8090' and host_env['TEAMCODE_MCP_DATA_DIR']['value'] == '/var/mcp'
assert host_env['TEAMCODE_MCP_LAUNCHERS']['value'] == 'npm,pypi'
assert 'TEAMCODE_MCP_NPM_REGISTRY' not in host_env and 'TEAMCODE_MCP_PYPI_INDEX' not in host_env
assert host_env['TEAMCODE_MCP_TOKEN']['valueFrom']['secretKeyRef'] == {'name': 'test-teamcode-mcp', 'key': 'token'}
# No database, no encryption key: the host reads no key of the operator's Secret.
assert not any(name.startswith('TEAMCODE_DATABASE') or name == 'TEAMCODE_ENCRYPTION_KEY' for name in host_env)
assert 'teamcode-secrets' not in yaml.safe_dump(host)
# Labels are disjoint: the server's Service, PDB and the MySQL policy never select the host, and the host's Service
# never selects a server pod.
server_deployment = server(documents)
server_labels = server_deployment['spec']['template']['metadata']['labels']
server_service = next(doc for doc in documents if doc['kind'] == 'Service' and doc['metadata']['name'] == 'test-teamcode')
pdb = next(doc for doc in documents if doc['kind'] == 'PodDisruptionBudget')
mysql_policy = next(doc for doc in documents if doc['kind'] == 'NetworkPolicy' and doc['metadata']['name'] == 'test-teamcode-mysql')
assert not matches(server_service['spec']['selector'], host_labels)
assert not matches(pdb['spec']['selector']['matchLabels'], host_labels)
assert not matches(mysql_policy['spec']['ingress'][0]['from'][0]['podSelector']['matchLabels'], host_labels)
assert not matches(server_deployment['spec']['selector']['matchLabels'], host_labels)
assert matches(objects['Service']['spec']['selector'], host_labels) and not matches(objects['Service']['spec']['selector'], server_labels)
assert objects['Service']['spec']['ports'] == [{'name': 'http', 'port': 8090, 'targetPort': 'http', 'protocol': 'TCP'}]
# The server reaches the host through its Service, with the same token.
server_env = {item['name']: item for item in server_deployment['spec']['template']['spec']['containers'][0]['env']}
assert server_env['TEAMCODE_MCP_URL']['value'] == 'http://test-teamcode-mcp:8090'
assert server_env['TEAMCODE_MCP_TOKEN']['valueFrom']['secretKeyRef'] == {'name': 'test-teamcode-mcp', 'key': 'token'}
# The generated Secret: 48 random alphanumeric characters, kept on uninstall, and one value per render shared by the
# Secret and both checksum annotations (so a rotation rolls the server and the host together).
secret = objects['Secret']
assert secret['metadata']['annotations']['helm.sh/resource-policy'] == 'keep' and set(secret['data']) == {'token'}
assert re.fullmatch(r'[A-Za-z0-9]{48}', base64.b64decode(secret['data']['token']).decode())
checksum = hashlib.sha256(secret['data']['token'].encode()).hexdigest()
assert host_pod['metadata']['annotations']['checksum/mcp-token'] == checksum
assert server_deployment['spec']['template']['metadata']['annotations']['checksum/mcp-token'] == checksum
# The host's NetworkPolicy: ingress from this release's server pods on 8090 only; egress to DNS and public addresses,
# with exactly the private ranges of the cloud session policy (SessionNetworkPolicy.kt) and their NAT64/6to4 forms.
policy = objects['NetworkPolicy']['spec']
assert matches(policy['podSelector']['matchLabels'], host_labels) and policy['policyTypes'] == ['Ingress', 'Egress']
(ingress,) = policy['ingress']
assert ingress['ports'] == [{'protocol': 'TCP', 'port': 8090}]
(peer,) = ingress['from']
assert set(peer) == {'podSelector'} and matches(peer['podSelector']['matchLabels'], server_labels)
assert not matches(peer['podSelector']['matchLabels'], host_labels)
dns, internet_v4, internet_v6 = policy['egress']
assert dns == {'ports': [{'protocol': 'UDP', 'port': 53}, {'protocol': 'TCP', 'port': 53}]}
source = open('infrastructure/src/main/kotlin/me/nathanfallet/teamcode/infrastructure/cloud/SessionNetworkPolicy.kt').read()


def kotlin_list(name):
    return re.findall(r'"([0-9a-f.:]+/\d+)"', re.search(rf'val {name} = listOf\((.*?)\)\n', source, re.S).group(1))


private_v4, private_v6 = kotlin_list('PRIVATE_V4'), kotlin_list('PRIVATE_V6')
assert len(private_v4) >= 10 and len(private_v6) >= 5


def embedded(cidr):
    network = ipaddress.ip_network(cidr)
    address = int(network.network_address)
    return [str(ipaddress.ip_network(((0x64ff9b << 96) | address, 96 + network.prefixlen))),
            str(ipaddress.ip_network(((0x2002 << 112) | (address << 80), 16 + network.prefixlen)))]


def canonical(cidrs):
    return [str(ipaddress.ip_network(c)) for c in cidrs]


(block_v4,) = internet_v4['to']
(block_v6,) = internet_v6['to']
assert block_v4['ipBlock']['cidr'] == '0.0.0.0/0' and canonical(block_v4['ipBlock']['except']) == private_v4
assert block_v6['ipBlock']['cidr'] == '::/0'
assert canonical(block_v6['ipBlock']['except']) == private_v6 + [c for cidr in private_v4 for c in embedded(cidr)]
for block in (block_v4, block_v6):
    outer = ipaddress.ip_network(block['ipBlock']['cidr'])
    assert all(ipaddress.ip_network(c).subnet_of(outer) and ipaddress.ip_network(c) != outer for c in block['ipBlock']['except'])
# Operator settings: blocked ranges (IPv4 also in NAT64/6to4 form), lockdown, extra rules, mirrors, launchers.
values = {'mcp': {'npmRegistry': 'https://npm.example.org', 'pypiIndex': 'https://pypi.example.org/simple', 'launchers': ['npm'],
                  'networkPolicy': {'blockedCidrs': ['203.0.113.0/24', '2001:db8::/32'], 'extraEgress': [
                      {'to': [{'ipBlock': {'cidr': '10.20.0.0/16'}}], 'ports': [{'protocol': 'TCP', 'port': 443}]}]}}}
with tempfile.NamedTemporaryFile('w', suffix='.yaml') as handle:
    yaml.safe_dump(values, handle)
    handle.flush()
    objects = mcp_objects(render('-f', handle.name))
    egress = objects['NetworkPolicy']['spec']['egress']
    assert '203.0.113.0/24' in egress[1]['to'][0]['ipBlock']['except']
    assert set(canonical(['2001:db8::/32'] + embedded('203.0.113.0/24'))) <= set(canonical(egress[2]['to'][0]['ipBlock']['except']))
    assert egress[3] == values['mcp']['networkPolicy']['extraEgress'][0]
    host_env = {item['name']: item.get('value') for item in objects['Deployment']['spec']['template']['spec']['containers'][0]['env']}
    assert host_env['TEAMCODE_MCP_NPM_REGISTRY'] == 'https://npm.example.org' and host_env['TEAMCODE_MCP_PYPI_INDEX'] == 'https://pypi.example.org/simple'
    assert host_env['TEAMCODE_MCP_LAUNCHERS'] == 'npm'
    values['mcp']['networkPolicy']['lockdown'] = True
    handle.seek(0)
    handle.truncate()
    yaml.safe_dump(values, handle)
    handle.flush()
    egress = mcp_objects(render('-f', handle.name))['NetworkPolicy']['spec']['egress']
    assert egress == [dns, values['mcp']['networkPolicy']['extraEgress'][0]], egress
assert 'NetworkPolicy' not in mcp_objects(render('--set', 'mcp.networkPolicy.enabled=false'))
# The host follows the server's tag: image.tag, then the released chart's appVersion; its own tag wins when set.
objects = mcp_objects(render('--set', 'image.tag=sha-abc'))
assert objects['Deployment']['spec']['template']['spec']['containers'][0]['image'] == 'nathanfallet/teamcode-mcp:sha-abc'
objects = mcp_objects(render('--set', 'image.tag=sha-abc', '--set', 'mcp.image.tag=sha-def'))
assert objects['Deployment']['spec']['template']['spec']['containers'][0]['image'] == 'nathanfallet/teamcode-mcp:sha-def'
with tempfile.TemporaryDirectory() as packaged:
    subprocess.run(['helm', 'package', chart, '--destination', packaged, '--version', '9.8.7', '--app-version', '9.8.7'], check=True, capture_output=True)
    container = mcp_objects(render(chart_path=f'{packaged}/teamcode-9.8.7.tgz'))['Deployment']['spec']['template']['spec']['containers'][0]
    assert container['image'] == 'nathanfallet/teamcode-mcp:9.8.7' and container['imagePullPolicy'] == 'IfNotPresent', container['image']
# An operator's token: no generated Secret, both Deployments read the given key.
documents = render('--set', 'mcp.token.existingSecret=my-mcp', '--set', 'mcp.token.key=value')
objects = mcp_objects(documents)
assert 'Secret' not in objects and not any(doc['kind'] == 'Secret' for doc in documents)
reference = {'name': 'my-mcp', 'key': 'value'}
assert {i['name']: i for i in objects['Deployment']['spec']['template']['spec']['containers'][0]['env']}['TEAMCODE_MCP_TOKEN']['valueFrom']['secretKeyRef'] == reference
server_pod = server(documents)['spec']['template']
assert {i['name']: i for i in server_pod['spec']['containers'][0]['env']}['TEAMCODE_MCP_TOKEN']['valueFrom']['secretKeyRef'] == reference
assert server_pod['metadata']['annotations']['checksum/mcp-token'] == objects['Deployment']['spec']['template']['metadata']['annotations']['checksum/mcp-token']
# Off: nothing of the host is rendered and the server gets no MCP variable or annotation.
for flags in (['--set', 'mcp.enabled=false'], ['--set', 'mcp.enabled=false', '--set', 'podAnnotations.a=b']):
    documents = render(*flags)
    assert not any('-mcp' in doc['metadata']['name'] for doc in documents)
    server_pod = server(documents)['spec']['template']
    assert not any(item['name'].startswith('TEAMCODE_MCP_') for item in server_pod['spec']['containers'][0]['env'])
    assert 'checksum/mcp-token' not in (server_pod['metadata'].get('annotations') or {})
assert 'annotations' not in server(render('--set', 'mcp.enabled=false'))['spec']['template']['metadata']
# A release upgraded with --reuse-values from a chart without the `mcp` block has no .Values.mcp: no host, no error.
with tempfile.TemporaryDirectory() as old_dir:
    import shutil
    old_chart = f'{old_dir}/teamcode'
    shutil.copytree(chart, old_chart)
    with open(f'{old_chart}/values.yaml') as handle:
        old_values = yaml.safe_load(handle)
    del old_values['mcp']
    with open(f'{old_chart}/values.yaml', 'w') as handle:
        yaml.safe_dump(old_values, handle)
    documents = render(chart_path=old_chart)
    assert not any('-mcp' in doc['metadata']['name'] for doc in documents)
# Misconfigurations fail at render time.
for flags in (['--set', 'mcp.drainSeconds=90'], ['--set', 'mcp.networkPolicy.blockedCidrs={0.0.0.0/0}'], ['--set', 'mcp.networkPolicy.blockedCidrs={10.0.0.1}']):
    result = subprocess.run(['helm', 'template', 'test', chart, '--set', 'publicUrl=https://teamcode.example.org', *flags], capture_output=True, text=True)
    assert result.returncode != 0 and 'mcp.' in result.stderr, (flags, result.stderr)
print('Helm contracts passed for bundled/external MySQL, HPA, ingress (response headers untouched), secrets, probes, draining, the MySQL network policy, the session egress settings and the MCP host (security, labels, token, network policy, on/off).')
