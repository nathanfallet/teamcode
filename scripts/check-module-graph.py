#!/usr/bin/env python3
"""Enforce production module boundaries without requiring another repository."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
allowed = {
    'api': set(), 'client': {'api'}, 'config': set(), 'cli': {'client', 'config'}, 'domain': set(),
    'infrastructure': {'domain'}, 'presentation': {'api', 'domain'},
    'app': {'api', 'domain', 'infrastructure', 'presentation'},
    'companion': {'client'}, 'companion-android': {'companion'}, 'companion-desktop': {'companion', 'config', 'client'},
}
for module, dependencies in allowed.items():
    build = (root / module / 'build.gradle.kts').read_text()
    # Test-only integration of the typed client and HTTP routes is intentional.
    build = re.sub(r'^.*testImplementation\(projects\..*$', '', build, flags=re.M)
    # The same for a multiplatform test source set (`jvmTest.dependencies { ... }`).
    build = re.sub(r'^\s*\w+Test\.dependencies \{.*?^\s*\}', '', build, flags=re.M | re.S)
    actual = set(re.findall(r'projects\.([A-Za-z]+)', build))
    assert actual == dependencies, f'{module}: expected {dependencies}, got {actual}'
    for source in (root / module / 'src').rglob('*.kt'):
        if any('Test' in part or part == 'test' for part in source.parts):
            continue
        for imported in re.findall(r'^import me\.nathanfallet\.teamcode\.([a-z]+)', source.read_text(), re.M):
            transitive = dependencies | {'api'} if module in ('cli', 'companion', 'companion-desktop') else dependencies
            assert imported == module or imported in transitive, f'{source}: forbidden import {imported}'
# Inside the companion app, as in the server: the domain knows neither the API contracts nor the typed client, and
# screens and view models reach implementations only through the di package.
companion = root / 'companion' / 'src'
for source in companion.rglob('*.kt'):
    if any('Test' in part or part == 'test' for part in source.parts):
        continue
    imports = re.findall(r'^import (me\.nathanfallet\.teamcode\.[A-Za-z0-9_.]+)', source.read_text(), re.M)
    if '/companion/domain/' in source.as_posix():
        for imported in imports:
            assert not imported.startswith(('me.nathanfallet.teamcode.api', 'me.nathanfallet.teamcode.client')), f'{source}: the domain imports {imported}'
            assert not imported.startswith(('me.nathanfallet.teamcode.companion.infrastructure', 'me.nathanfallet.teamcode.companion.presentation', 'me.nathanfallet.teamcode.companion.di')), f'{source}: the domain imports {imported}'
    if '/companion/presentation/' in source.as_posix():
        for imported in imports:
            assert not imported.startswith(('me.nathanfallet.teamcode.companion.infrastructure', 'me.nathanfallet.teamcode.api', 'me.nathanfallet.teamcode.client')), f'{source}: presentation imports {imported}'
# All application and fixture queries must use the typed Exposed DSL. Flyway's generated
# migration resources are intentionally separate from executable Kotlin sources.
raw_query = re.compile(r'"(?:SELECT\s|INSERT\s+INTO\s|UPDATE\s+\w+\s+SET\s|DELETE\s+FROM\s|CREATE\s+TABLE\s|ALTER\s+TABLE\s)', re.I)
for source in root.glob('*/src/**/*.kt'):
    content = source.read_text()
    assert not raw_query.search(content), f'{source}: raw SQL is forbidden; use the Exposed DSL'
    assert not re.search(r'\b(?:prepareStatement|prepareCall|createStatement)\s*\(', content), f'{source}: raw JDBC execution is forbidden'
    assert not re.search(r'\.exec\s*\(', content), f'{source}: execute typed Exposed statements, not SQL strings'
print('Production module boundaries and Exposed query conventions verified.')
