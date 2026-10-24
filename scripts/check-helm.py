#!/usr/bin/env python3
"""Render both database modes and check deployment contracts without a Kubernetes connection."""
import json
import subprocess
import tempfile
import yaml

chart = 'helm/teamcode'
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
    deployment = next(doc for doc in documents if doc['kind'] == 'Deployment')
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
    assert not any(doc['kind'] == 'Secret' for doc in documents)
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
    policies = [doc for doc in documents if doc['kind'] == 'NetworkPolicy']
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
env = {item['name']: item['value'] for item in next(doc for doc in documents if doc['kind'] == 'Deployment')['spec']['template']['spec']['containers'][0]['env'] if 'value' in item}
assert env['TEAMCODE_CLOUD_SESSION_EGRESS_LOCKDOWN'] == 'true'
assert env['TEAMCODE_CLOUD_SESSION_EGRESS_BLOCKED_CIDRS'] == '203.0.113.0/24,2001:db8::/32'
assert json.loads(env['TEAMCODE_CLOUD_SESSION_EGRESS_EXTRA']) == values['cloudSessions']['networkPolicy']['extraEgress']
assert not any(doc['kind'] == 'NetworkPolicy' for doc in documents), 'mysql.networkPolicy.enabled=false renders no policy'
# A null list (an empty `extraEgress:` key) still renders a JSON array, and `enabled: false` reaches the server as written.
rendered = subprocess.run(['helm', 'template', 'test', chart, '--set', 'publicUrl=https://teamcode.example.org', '--set', 'cloudSessions.networkPolicy.extraEgress=null',
                           '--set', 'cloudSessions.networkPolicy.blockedCidrs=null', '--set', 'cloudSessions.networkPolicy.enabled=false'], check=True, capture_output=True, text=True).stdout
env = {item['name']: item.get('value') for item in next(doc for doc in yaml.safe_load_all(rendered) if doc and doc['kind'] == 'Deployment')['spec']['template']['spec']['containers'][0]['env']}
assert env['TEAMCODE_CLOUD_SESSION_EGRESS_EXTRA'] == '[]' and env['TEAMCODE_CLOUD_SESSION_EGRESS_BLOCKED_CIDRS'] == ''
assert env['TEAMCODE_CLOUD_SESSION_NETWORK_POLICY'] == 'false'
rendered = subprocess.run(['helm', 'template', 'test', chart, '--set', 'publicUrl=https://teamcode.example.org', '--set', 'trustedProxies={10.244.0.0/16,fd00::/8}'], check=True, capture_output=True, text=True).stdout
env = {item['name']: item.get('value') for item in next(doc for doc in yaml.safe_load_all(rendered) if doc and doc['kind'] == 'Deployment')['spec']['template']['spec']['containers'][0]['env']}
assert env['TEAMCODE_TRUSTED_PROXIES'] == '10.244.0.0/16,fd00::/8'
rendered = subprocess.run(['helm', 'template', 'test', chart, '--set', 'publicUrl=https://teamcode.example.org', '--set', 'webhooks.allowPrivateTargets=true'], check=True, capture_output=True, text=True).stdout
env = {item['name']: item.get('value') for item in next(doc for doc in yaml.safe_load_all(rendered) if doc and doc['kind'] == 'Deployment')['spec']['template']['spec']['containers'][0]['env']}
assert env['TEAMCODE_WEBHOOK_PRIVATE_TARGETS'] == 'true'
# A released chart deploys its own version (audit L8): helm package sets appVersion, which the image tag defaults to,
# and an exact version is not pulled again on every start.
with tempfile.TemporaryDirectory() as packaged:
    subprocess.run(['helm', 'package', chart, '--destination', packaged, '--version', '9.8.7', '--app-version', '9.8.7'], check=True, capture_output=True)
    rendered = subprocess.run(['helm', 'template', 'test', f'{packaged}/teamcode-9.8.7.tgz', '--set', 'publicUrl=https://teamcode.example.org'], check=True, capture_output=True, text=True).stdout
    container = next(doc for doc in yaml.safe_load_all(rendered) if doc and doc['kind'] == 'Deployment')['spec']['template']['spec']['containers'][0]
    assert container['image'] == 'nathanfallet/teamcode:9.8.7' and container['imagePullPolicy'] == 'IfNotPresent', container['image']
    rendered = subprocess.run(['helm', 'template', 'test', f'{packaged}/teamcode-9.8.7.tgz', '--set', 'publicUrl=https://teamcode.example.org', '--set', 'image.tag=latest'], check=True, capture_output=True, text=True).stdout
    container = next(doc for doc in yaml.safe_load_all(rendered) if doc and doc['kind'] == 'Deployment')['spec']['template']['spec']['containers'][0]
    assert container['image'] == 'nathanfallet/teamcode:latest' and container['imagePullPolicy'] == 'Always'
# Misconfigured installations must fail at render time.
for flags in ([], ['--set', 'publicUrl=https://teamcode.example.org', '--set', 'mysql.enabled=false']):
    assert subprocess.run(['helm', 'template', 'test', chart, *flags], capture_output=True).returncode != 0
print('Helm contracts passed for bundled/external MySQL, HPA, ingress (response headers untouched), secrets, probes, draining, the MySQL network policy and the session egress settings.')
