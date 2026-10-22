#!/usr/bin/env python3
"""Render both database modes and check deployment contracts without a Kubernetes connection."""
import subprocess
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
    if external:
        ingress = next(doc for doc in documents if doc['kind'] == 'Ingress')
        assert ingress['metadata']['annotations']['nginx.ingress.kubernetes.io/proxy-buffering'] == 'off'
        # Artifact content relies on the application's Content-Security-Policy sandbox on every response: the ingress
        # must pass response headers through, never hide or rewrite them.
        annotations = ingress['metadata']['annotations']
        assert not any('snippet' in key or 'hide-headers' in key or 'custom-headers' in key for key in annotations), annotations
        assert 'content-security-policy' not in yaml.safe_dump(ingress).lower()
# Misconfigured installations must fail at render time.
for flags in ([], ['--set', 'publicUrl=https://teamcode.example.org', '--set', 'mysql.enabled=false']):
    assert subprocess.run(['helm', 'template', 'test', chart, *flags], capture_output=True).returncode != 0
print('Helm contracts passed for bundled/external MySQL, HPA, ingress (response headers untouched), secrets, probes and draining.')
