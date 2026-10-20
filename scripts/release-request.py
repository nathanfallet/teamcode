#!/usr/bin/env python3
"""Fail closed on manual publication inputs and bind accepted assets to their source/version."""
import argparse
import json
import os
from pathlib import Path
import re


def enabled(value):
    return value is True or value == 'true'


def validate_request(event_name, ref, inputs, version):
    if event_name != 'workflow_dispatch' or not enabled(inputs.get('publish_release')):
        return False
    stable = r'(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})'
    if (ref != 'refs/heads/main' or not enabled(inputs.get('full_validation'))
            or inputs.get('validation_version') != version or not re.fullmatch(stable, version)):
        raise ValueError('Publication requires main, full validation and an exact stable unprefixed version.')
    return True


def validate_manifest(path, version, source):
    manifest = json.loads(Path(path).read_text())
    if (manifest.get('version') != version or manifest.get('tag') != version
            or manifest.get('sourceCommit') != source):
        raise ValueError('Accepted release metadata does not match this version/source.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--manifest', type=Path)
    args = parser.parse_args()
    event_path = os.environ.get('GITHUB_EVENT_PATH')
    event = json.loads(Path(event_path).read_text()) if event_path else {}
    version = os.environ.get('ORG_GRADLE_PROJECT_teamcodeVersion', '')
    validate_request(os.environ.get('GITHUB_EVENT_NAME'), os.environ.get('GITHUB_REF'), event.get('inputs', {}), version)
    if args.manifest:
        validate_manifest(args.manifest, version, os.environ.get('GITHUB_SHA'))
    print('Release request and selected metadata are consistent.')


if __name__ == '__main__':
    main()
