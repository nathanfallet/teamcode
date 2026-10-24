#!/usr/bin/env python3
"""Exercise the service step of the actual native install and update on the real OS service manager (decisions 10, 11).

The service is installed at login (never at boot, never elevated), repaired once the OS has it disabled and unloaded,
left alone when it is fine, skipped by --no-service and after `service uninstall`. Everything lives in a temporary
HOME and configuration; the only shared state is a randomly named user registration, removed at the end. Without a
saved login the service waits for one and binds no port. Linux needs a running systemd user manager.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, required=True, help='A signed local bundle for this target.')
    args = parser.parse_args()
    binary, bundle = str(args.binary.resolve()), str(args.bundle.resolve())
    windows, macos = os.name == 'nt', sys.platform == 'darwin'

    with tempfile.TemporaryDirectory(prefix='teamcode-service-setup-') as directory:
        root = Path(directory)
        home = root / 'home'; home.mkdir()
        config = root / 'configuration' / 'teamcode' / 'config.json'
        install_root = root / 'installation'
        launcher = install_root / 'bin' / ('teamcode.exe' if windows else 'teamcode')
        env = dict(os.environ, HOME=str(home), USERPROFILE=str(home), XDG_CONFIG_HOME=str(root / 'configuration'),
                   TEAMCODE_DISABLE_AUTOUPDATE='1')
        env.pop('TEAMCODE_NO_SERVICE', None)

        def run(executable, *arguments):
            result = subprocess.run([str(executable), *arguments], env=env, stdin=subprocess.DEVNULL, capture_output=True,
                                    text=True, timeout=180)
            output = result.stdout + result.stderr
            assert result.returncode == 0, f'{arguments} failed ({result.returncode}): {output}'
            return output

        def service_line(output):
            lines = [line for line in output.splitlines() if line.startswith('Local TeamCode service:')]
            assert len(lines) == 1, 'Exactly one line about the service: ' + output
            return lines[0]

        def settings():
            path = config.with_name('service.json')
            return json.loads(path.read_text()) if path.exists() else None

        def os_manager(*arguments, check=True):
            result = subprocess.run(list(arguments), capture_output=True, text=True, timeout=60)
            assert not check or result.returncode == 0, f'{arguments}: {result.stdout}{result.stderr}'
            return result

        def registered_at_login(saved):
            label = 'me.nathanfallet.teamcode.' + saved['id']
            if macos:
                uid = str(os.getuid())
                disabled = os_manager('/bin/launchctl', 'print-disabled', 'gui/' + uid).stdout
                return (os_manager('/bin/launchctl', 'print', f'gui/{uid}/{label}', check=False).returncode == 0
                        and f'"{label}" => disabled' not in disabled and f'"{label}" => true' not in disabled)
            if windows:
                state = subprocess.run(['powershell.exe', '-NoLogo', '-NoProfile', '-NonInteractive', '-Command',
                                        '(Get-ScheduledTask -TaskName $env:TEAMCODE_TEST_TASK -ErrorAction SilentlyContinue).State'],
                                       env=dict(os.environ, TEAMCODE_TEST_TASK=label), capture_output=True, text=True, timeout=60)
                return state.stdout.strip() in ('Ready', 'Running')
            return (os_manager('/usr/bin/systemctl', '--user', 'is-enabled', '--quiet', label + '.service', check=False).returncode == 0
                    and os_manager('/usr/bin/systemctl', '--user', 'is-active', '--quiet', label + '.service', check=False).returncode == 0)

        installed = False
        try:
            # A CLI installation ends with the service, started at login, even before teamcode login.
            line = service_line(run(binary, 'install', '--no-path', '--bundle', bundle, '--root', str(install_root)))
            assert 'installed and started' in line and 'starts at login' in line, line
            installed = True
            saved = settings()
            assert saved and saved.get('atBoot', False) is False, 'Login startup by default'
            assert Path(saved['executable']).resolve() == launcher.resolve(), 'The stable launcher, which follows updates'
            if macos: assert saved['registration'].startswith(str(home / 'Library/LaunchAgents')), saved['registration']
            assert registered_at_login(saved), 'Not registered, enabled and loaded at login'

            # Already good: nothing to do, same service.
            line = service_line(run(launcher, 'install', '--no-path', '--bundle', bundle, '--root', str(install_root)))
            assert 'nothing to change' in line, line
            assert settings() == saved

            # Disabled and unloaded behind TeamCode's back: the next update repairs it in place.
            label = 'me.nathanfallet.teamcode.' + saved['id']
            if macos:
                target = f'gui/{os.getuid()}/{label}'
                os_manager('/bin/launchctl', 'disable', target)
                os_manager('/bin/launchctl', 'bootout', target, check=False)
            elif windows:
                run(launcher, 'service', 'stop')  # Disables the logon task and stops it.
            else:
                os_manager('/usr/bin/systemctl', '--user', 'disable', '--now', label + '.service')
            assert not registered_at_login(saved)
            line = service_line(run(launcher, 'update', '--bundle', bundle, '--root', str(install_root)))
            assert 'repaired' in line and 'enabled at login' in line, line
            assert settings() == saved, 'Repaired, not replaced'
            assert registered_at_login(saved)

            # Opt-outs: once, then persisted by an uninstall.
            assert 'not checked (--no-service)' in service_line(run(launcher, 'update', '--no-service', '--bundle', bundle, '--root', str(install_root)))
            run(launcher, 'service', 'uninstall')
            installed = False
            assert settings() is None and not Path(saved['registration']).exists()
            line = service_line(run(launcher, 'update', '--bundle', bundle, '--root', str(install_root)))
            assert 'automatic setup is off' in line, line
            assert settings() is None, 'An uninstall is not undone by the next update'
            print('PASS: service installed at login by install, left alone when fine, repaired by update, opt-outs respected')
        finally:
            if installed: subprocess.run([str(launcher), 'service', 'uninstall'], env=env, stdin=subprocess.DEVNULL, timeout=180)


if __name__ == '__main__': main()
