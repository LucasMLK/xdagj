#!/usr/bin/env python3
"""
Helper utility for orchestrating local multi-node devnet suites.

Features:
  * Rebuild and distribute fresh XDAGJ / pool / miner artifacts into suite folders
  * Start, stop, and inspect every process (node, pool, miner)
  * Query HTTP APIs to ensure multi-node chain data stays in sync
  * Re-render node/pool/miner config templates when suite parameters change
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Optional, Set, Tuple
from urllib import error as urlerror
from urllib import request


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONFIG = REPO_ROOT / "test-nodes" / "devnet-manager.json"
COMPONENT_ORDER = ("node", "pool", "miner")


class DevnetManager:
    def __init__(self, config_path: Path) -> None:
        if not config_path.exists():
            raise SystemExit(f"Config file not found: {config_path}")
        with config_path.open(encoding="utf-8") as handle:
            self._config = json.load(handle)
        self._suites = self._load_suites()
        self._cleanup_config = self._config.get("cleanup", {})

    # ----- public commands -------------------------------------------------

    def update(
        self,
        suites: Iterable[str],
        components: Iterable[str],
        build: bool,
        node_jar: Optional[Path],
        pool_jar: Optional[Path],
        miner_jar: Optional[Path],
        cleanup_override: Optional[bool],
    ) -> None:
        suites = tuple(self._select_suites(suites))
        jar_map = {
            "node": node_jar,
            "pool": pool_jar,
            "miner": miner_jar,
        }

        if build:
            for artifact_name in COMPONENT_ORDER:
                if jar_map.get(artifact_name):
                    continue
                artifact_config = self._artifact_config(artifact_name)
                if not artifact_config:
                    continue
                for command in self._build_commands(artifact_config):
                    self._run(command, cwd=self._resolve_project_dir(artifact_config))

        for artifact_name in COMPONENT_ORDER:
            if jar_map.get(artifact_name):
                jar_map[artifact_name] = Path(jar_map[artifact_name]).resolve()
                continue
            artifact_path = self._find_artifact_from_config(artifact_name)
            if artifact_path:
                jar_map[artifact_name] = artifact_path

        cleanup_enabled = (
            cleanup_override
            if cleanup_override is not None
            else self._cleanup_config.get("enabled", False)
        )
        for suite_name, component_name, component, context in self._iterate_components(suites, components):
            if cleanup_enabled and self._should_cleanup_component(component_name):
                self._cleanup_component(component, suite_name, component_name)
            artifact = component.get("artifact")
            if not artifact:
                continue
            ref = component.get("artifactRef", component_name)
            source = jar_map.get(ref)
            if not source:
                print(f"[skip] {suite_name}/{component_name}: no artifact source supplied (ref={ref})")
                continue
            dest = self._component_path(component, artifact)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if source.resolve() == dest.resolve():
                print(f"[skip] {suite_name}/{component_name}: source equals destination ({dest})")
            else:
                shutil.copy2(source, dest)
                print(f"[ok] Copied {source.name} -> {dest}")
            self._render_component_files(component, context)

    def start(self, suites: Iterable[str], components: Iterable[str]) -> None:
        for suite_name, component_name, component, _ in self._iterate_components(suites, components):
            self._start_component(suite_name, component_name, component)

    def stop(self, suites: Iterable[str], components: Iterable[str]) -> None:
        for suite_name, component_name, component, _ in self._iterate_components(suites, components):
            self._stop_component(suite_name, component_name, component)

    def status(self, suites: Iterable[str], components: Iterable[str]) -> None:
        rows: List[Tuple[str, str, str, str]] = []
        for suite_name, component_name, component, _ in self._iterate_components(suites, components):
            pid = self._read_pid(component)
            alive = self._pid_is_alive(pid) if pid else False
            info = self._format_component_status(component_name, component, pid, alive)
            rows.append((suite_name, component_name, "running" if alive else "stopped", info))

        if not rows:
            print("No suites/components matched")
            return

        suite_w = max(len(r[0]) for r in rows)
        comp_w = max(len(r[1]) for r in rows)
        state_w = max(len(r[2]) for r in rows)
        for suite, comp, state, info in rows:
            print(
                f"{suite.ljust(suite_w)} | {comp.ljust(comp_w)} | "
                f"{state.ljust(state_w)} | {info}"
            )

    def check_consistency(self, suites: Iterable[str]) -> None:
        suites = tuple(self._select_suites(suites))
        if not suites:
            print("No suites configured; aborting")
            return

        results: List[Tuple[str, Optional["NodeSnapshot"]]] = []
        for suite in suites:
            component = self._component_data(suite, "node")
            http_base = component.get("httpAddress")
            if not http_base:
                results.append((suite, None))
                continue
            snapshot = self._fetch_node_snapshot(http_base.rstrip("/"))
            results.append((suite, snapshot))

        if not results:
            print("No node components available for consistency check")
            return

        reference = next((snap for _, snap in results if snap), None)
        headers = ("suite", "height", "hash", "notes")
        widths = [len(h) for h in headers]
        formatted_rows: List[Tuple[str, str, str, str]] = []

        for suite, snapshot in results:
            if not snapshot:
                row = (suite, "-", "-", "HTTP endpoint missing or unreachable")
            else:
                notes = []
                if reference and snapshot.height is not None:
                    if snapshot.height != reference.height:
                        delta = snapshot.height - reference.height
                        notes.append(f"heightΔ={delta:+d}")
                    if snapshot.hash and reference.hash and snapshot.hash != reference.hash:
                        notes.append("hash mismatch")
                elif snapshot.height is None:
                    notes.append("no height")
                row = (
                    suite,
                    snapshot.height_repr(),
                    snapshot.hash or "-",
                    ", ".join(notes) if notes else "in-sync",
                )
            formatted_rows.append(row)
            widths = [max(widths[i], len(row[i])) for i in range(len(row))]

        header_line = " | ".join(h.upper().ljust(widths[i]) for i, h in enumerate(headers))
        print(header_line)
        print("-" * len(header_line))
        for row in formatted_rows:
            print(" | ".join(row[i].ljust(widths[i]) for i in range(len(row))))

    # ----- internal helpers ------------------------------------------------

    def _iterate_components(
        self, suites: Iterable[str], components: Iterable[str]
    ) -> Iterator[Tuple[str, str, Dict, Dict]]:
        suites = tuple(self._select_suites(suites))
        if not suites:
            raise SystemExit("No suites matched the selection")

        components = tuple(self._select_components(components))
        for suite in suites:
            suite_data = self._suites[suite]
            suite_components = suite_data["components"]
            context = suite_data.get("_context", {})
            for component_name in components:
                if component_name not in suite_components:
                    continue
                yield suite, component_name, suite_components[component_name], context

    def _select_suites(self, suites: Iterable[str]) -> Iterable[str]:
        if suites:
            known = set(self._suites.keys())
            filtered = []
            for suite in suites:
                if suite not in known:
                    raise SystemExit(f"Unknown suite '{suite}'. Known suites: {', '.join(sorted(known))}")
                filtered.append(suite)
            return filtered
        return self._suites.keys()

    def _select_components(self, components: Iterable[str]) -> Iterable[str]:
        if components:
            invalid = set(components) - set(COMPONENT_ORDER)
            if invalid:
                raise SystemExit(f"Unknown components: {', '.join(sorted(invalid))}")
            return components
        return COMPONENT_ORDER

    def _component_path(self, component: Dict, relative: str) -> Path:
        workdir = self._component_workdir(component)
        return workdir / relative

    def _component_workdir(self, component: Dict) -> Path:
        path = component.get("workdir")
        if not path:
            raise SystemExit("Component missing 'workdir' setting")
        return (REPO_ROOT / path).resolve()

    def _run(self, cmd: Iterable[str], cwd: Path) -> None:
        command = list(cmd)
        print(f"[cmd] {' '.join(command)} (cwd={cwd})")
        result = subprocess.run(command, cwd=cwd)
        if result.returncode != 0:
            raise SystemExit(result.returncode)

    def _artifact_config(self, name: str) -> Optional[Dict]:
        artifacts = self._config.get("artifacts", {})
        return artifacts.get(name)

    @staticmethod
    def _build_commands(artifact_config: Dict) -> List[List[str]]:
        commands = artifact_config.get("build", [])
        if not commands:
            return []
        if isinstance(commands[0], str):
            return [commands]
        return [list(command) for command in commands]

    def _resolve_project_dir(self, artifact_config: Dict) -> Path:
        project = artifact_config.get("projectDir", ".")
        return (REPO_ROOT / project).resolve()

    def _find_artifact_from_config(self, name: str) -> Optional[Path]:
        artifact_config = self._artifact_config(name)
        if not artifact_config:
            if name == "node":
                return self._fallback_node_artifact()
            return None
        project_dir = self._resolve_project_dir(artifact_config)
        glob_pattern = artifact_config.get("jarGlob")
        if not glob_pattern:
            raise SystemExit(f"'jarGlob' missing for artifact '{name}' in config")
        candidates = sorted(
            project_dir.glob(glob_pattern),
            key=lambda path: path.stat().st_mtime,
        )
        if not candidates:
            raise SystemExit(
                f"No artifact found for '{name}' matching '{glob_pattern}' inside {project_dir}"
            )
        return candidates[-1]

    def _fallback_node_artifact(self) -> Path:
        candidates = []
        target_dir = REPO_ROOT / "target"
        for pattern in ("xdagj-*-executable.jar", "xdagj-*.jar"):
            candidates.extend(target_dir.glob(pattern))
        if not candidates:
            raise SystemExit(
                "No XDAGJ executable jar found under target/. Run 'python3 script/devnet_manager.py update --build' after a successful mvn package."
            )
        latest = max(candidates, key=lambda path: path.stat().st_mtime)
        return latest

    def _render_component_files(self, component: Dict, context: Dict) -> None:
        files = component.get("files")
        if not files:
            return
        for entry in files:
            destination = entry.get("destination")
            if not destination:
                continue
            if "template" in entry:
                template_path = REPO_ROOT / entry["template"]
                template_text = template_path.read_text(encoding="utf-8")
            else:
                template_text = entry.get("content", "")
            use_format = entry.get("format", True)
            rendered = template_text.format(**context) if use_format else template_text
            target = self._component_path(component, destination)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(rendered, encoding="utf-8")
            print(f"[ok] Rendered template -> {target}")

    def _start_component(self, suite: str, name: str, component: Dict) -> None:
        pid = self._read_pid(component)
        if pid and self._pid_is_alive(pid):
            print(f"[skip] {suite}/{name} already running (pid {pid})")
            return

        args = component.get("startArgs")
        if not args:
            print(f"[skip] {suite}/{name} missing startArgs")
            return

        workdir = self._component_workdir(component)
        workdir.mkdir(parents=True, exist_ok=True)

        log_file = component.get("logFile", f"{name}.log")
        log_path = workdir / log_file
        log_path.parent.mkdir(parents=True, exist_ok=True)

        env = os.environ.copy()
        env.update({k: v for k, v in component.get("env", {}).items()})

        with log_path.open("ab") as log_handle:
            process = subprocess.Popen(
                args,
                cwd=workdir,
                env=env,
                stdout=log_handle,
                stderr=subprocess.STDOUT,
            )
        pid_file = self._component_path(component, component.get("pidFile", f"{name}.pid"))
        pid_file.write_text(str(process.pid), encoding="utf-8")
        print(f"[ok] Started {suite}/{name} (pid {process.pid}, log {log_path})")

    def _stop_component(self, suite: str, name: str, component: Dict) -> None:
        pid = self._read_pid(component)
        if not pid:
            print(f"[skip] {suite}/{name} not running (no pid file)")
            return
        if not self._pid_is_alive(pid):
            self._delete_pid_file(component)
            print(f"[skip] {suite}/{name} pid {pid} already dead")
            return

        print(f"[cmd] Stopping {suite}/{name} (pid {pid})")
        os.kill(pid, signal.SIGTERM)
        timeout = time.time() + component.get("shutdownGraceSeconds", 10)
        while time.time() < timeout:
            if not self._pid_is_alive(pid):
                break
            time.sleep(0.5)
        else:
            print(f"[warn] {suite}/{name} still alive; sending SIGKILL")
            os.kill(pid, signal.SIGKILL)
        self._delete_pid_file(component)
        print(f"[ok] Stopped {suite}/{name}")

    def _read_pid(self, component: Dict) -> Optional[int]:
        pid_file = component.get("pidFile")
        if not pid_file:
            return None
        path = self._component_path(component, pid_file)
        if not path.exists():
            return None
        try:
            return int(path.read_text(encoding="utf-8").strip())
        except ValueError:
            return None

    def _delete_pid_file(self, component: Dict) -> None:
        pid_file = component.get("pidFile")
        if not pid_file:
            return
        path = self._component_path(component, pid_file)
        if path.exists():
            path.unlink()

    @staticmethod
    def _pid_is_alive(pid: Optional[int]) -> bool:
        if not pid:
            return False
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False

    def _format_component_status(self, name: str, component: Dict, pid: Optional[int], alive: bool) -> str:
        details = []
        if pid:
            label = "pid" if alive else "pid(stale)"
            details.append(f"{label}={pid}")
        log_file = component.get("logFile")
        if log_file:
            details.append(f"log={log_file}")
        if name == "pool" and component.get("stratumPort"):
            healthy = self._check_tcp("127.0.0.1", component["stratumPort"])
            details.append("tcp=up" if healthy else "tcp=down")
        if name == "node" and component.get("httpAddress"):
            healthy = self._check_http(component["httpAddress"] + "/api/v1/network/syncing")
            details.append("http=up" if healthy else "http=down")
        if not details:
            return "-"
        return ", ".join(details)

    @staticmethod
    def _check_tcp(host: str, port: int, timeout: float = 0.5) -> bool:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.settimeout(timeout)
            try:
                sock.connect((host, port))
                return True
            except OSError:
                return False

    @staticmethod
    def _check_http(url: str, timeout: float = 0.5) -> bool:
        try:
            req = request.Request(url, method="GET")
            with request.urlopen(req, timeout=timeout):
                return True
        except Exception:
            return False

    def _component_data(self, suite: str, name: str) -> Dict:
        return self._suites[suite]["components"][name]

    def _load_suites(self) -> Dict[str, Dict]:
        suites = {name: data for name, data in self._config.get("suites", {}).items()}
        template = self._config.get("suiteTemplate")
        if template:
            generated = self._generate_suites_from_template(template)
            for name, data in generated.items():
                suites.setdefault(name, data)
        if not suites:
            raise SystemExit("No suites defined. Provide explicit entries under 'suites' or configure 'suiteTemplate'.")
        return suites

    def _generate_suites_from_template(self, template: Dict) -> Dict[str, Dict]:
        components_template = template.get("components")
        if not components_template:
            raise SystemExit("suiteTemplate must define 'components'.")
        count = int(template.get("count", 0))
        if count <= 0:
            return {}
        start_index = int(template.get("startIndex", 1))
        prefix = template.get("namePrefix", "suite")
        description_template = template.get("description", "")
        variable_defs = template.get("variables", {})
        contexts: List[Tuple[str, Dict]] = []
        for offset in range(count):
            index = start_index + offset
            suite_name = f"{prefix}{index}"
            context = {
                "index": index,
                "suite": suite_name,
            }
            for var_name, spec in variable_defs.items():
                if isinstance(spec, dict):
                    base = spec.get("base")
                    step = spec.get("step", 0)
                    if base is None:
                        raise SystemExit(f"Variable '{var_name}' in suiteTemplate is missing 'base'")
                    context[var_name] = base + step * offset
                else:
                    context[var_name] = spec
            contexts.append((suite_name, context))

        all_node_endpoints = [
            f"{ctx.get('node_host', '127.0.0.1')}:{ctx['node_port']}" for _, ctx in contexts
        ]

        suites: Dict[str, Dict] = {}
        for suite_name, context in contexts:
            context.setdefault("node_tag", f"xdagj-node-{context['index']}")
            node_host = context.get("node_host", "127.0.0.1")
            http_host = context.get("http_host", node_host)
            stratum_bind_host = context.get("stratum_bind_host", "0.0.0.0")
            miner_pool_host = context.get("miner_pool_host", "127.0.0.1")
            context["node_endpoint"] = f"{node_host}:{context['node_port']}"
            context["http_url"] = f"http://{http_host}:{context['http_port']}"
            context["http_host"] = http_host
            context["stratum_bind_host"] = stratum_bind_host
            context["miner_pool_host"] = miner_pool_host
            context.setdefault("pool_id", f"test-pool-{suite_name}")
            context.setdefault("pool_name", f"Test Pool for {suite_name}")
            context.setdefault("miner_worker_name", f"test-miner-{context['index']}")
            context.setdefault(
                "miner_worker_address", "nCR7vmdnVzsZ24VtqqurSmC7WLMF8Ymj"
            )
            context["white_ips"] = json.dumps(all_node_endpoints)
            suite_data = {
                "description": description_template.format(**context) if description_template else "",
                "components": {},
                "_context": context,
            }
            for comp_name, comp_template in components_template.items():
                suite_data["components"][comp_name] = self._apply_template(comp_template, context)
            suites[suite_name] = suite_data
        return suites

    def _apply_template(self, template: object, context: Dict[str, object]) -> object:
        if isinstance(template, str):
            return template.format(**context)
        if isinstance(template, list):
            return [self._apply_template(item, context) for item in template]
        if isinstance(template, dict):
            if "var" in template:
                var_name = template["var"]
                if var_name not in context:
                    raise SystemExit(f"Unknown variable '{var_name}' in suiteTemplate")
                return context[var_name]
            return {key: self._apply_template(value, context) for key, value in template.items()}
        return template

    def _cleanup_entries(self, component_name: str) -> List[Dict]:
        entries = []
        for entry in self._cleanup_config.get("paths", []):
            comp = entry.get("component")
            if comp in (component_name, "*"):
                entries.append(entry)
        return entries

    def _should_cleanup_component(self, component_name: str) -> bool:
        return bool(self._cleanup_entries(component_name))

    def _cleanup_component(self, component: Dict, suite: str, component_name: str) -> None:
        entries = self._cleanup_entries(component_name)
        if not entries:
            return
        pid = self._read_pid(component)
        if pid and self._pid_is_alive(pid):
            print(f"[warn] Skip cleanup for running {suite}/{component_name} (pid {pid})")
            return
        workdir = self._component_workdir(component)
        preserve_paths = {Path(p) for p in self._cleanup_config.get("preserve", [])}
        for entry in entries:
            rel = entry.get("relative")
            if not rel:
                continue
            rel_path = Path(rel)
            if self._is_preserved(rel_path, preserve_paths):
                print(f"[skip] {suite}/{component_name}: preserve {rel_path}")
                continue
            target = (workdir / rel_path).resolve()
            if not self._is_path_within(target, workdir):
                print(f"[warn] Skip cleanup outside workdir: {target}")
                continue
            self._remove_path(target, suite, component_name)

    @staticmethod
    def _is_preserved(target: Path, preserve_paths: Set[Path]) -> bool:
        for preserve in preserve_paths:
            try:
                preserve.relative_to(target)
                return True
            except ValueError:
                pass
            try:
                target.relative_to(preserve)
                return True
            except ValueError:
                pass
        return False

    @staticmethod
    def _is_path_within(path: Path, root: Path) -> bool:
        try:
            path.relative_to(root.resolve())
            return True
        except ValueError:
            return False

    @staticmethod
    def _remove_path(path: Path, suite: str, component_name: str) -> None:
        if not path.exists():
            print(f"[skip] {suite}/{component_name}: nothing to remove at {path}")
            return
        try:
            if path.is_file() or path.is_symlink():
                path.unlink()
            else:
                shutil.rmtree(path)
            print(f"[ok] Cleared {suite}/{component_name}: {path}")
        except Exception as exc:
            print(f"[warn] Failed to delete {path}: {exc}")

    def _fetch_node_snapshot(self, base_url: str) -> Optional["NodeSnapshot"]:
        block_number_url = f"{base_url}/api/v1/blocks/number"
        try:
            block_number_json = self._http_get_json(block_number_url)
        except urlerror.URLError as exc:
            print(f"[warn] Failed to fetch {block_number_url}: {exc}")
            return None

        block_hex = block_number_json.get("blockNumber") or block_number_json.get("block_number")
        height = self._parse_hex_height(block_hex) if block_hex else None
        block_hash = None
        if height is not None:
            detail_url = f"{base_url}/api/v1/blocks/{height}"
            try:
                detail_json = self._http_get_json(detail_url)
                if detail_json:
                    block_hash = detail_json.get("hash")
            except urlerror.URLError as exc:
                print(f"[warn] Failed to fetch {detail_url}: {exc}")
        return NodeSnapshot(height=height, hash=block_hash)

    @staticmethod
    def _parse_hex_height(value: str) -> Optional[int]:
        if value is None:
            return None
        value = value.strip()
        if not value:
            return None
        if value.startswith("0x"):
            return int(value, 16)
        return int(value)

    @staticmethod
    def _http_get_json(url: str) -> Dict:
        req = request.Request(url, headers={"Accept": "application/json"})
        with request.urlopen(req, timeout=5) as resp:
            content = resp.read()
            if not content:
                return {}
            return json.loads(content.decode("utf-8"))


@dataclass
class NodeSnapshot:
    height: Optional[int]
    hash: Optional[str]

    def height_repr(self) -> str:
        return str(self.height) if self.height is not None else "-"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Manage local XDAGJ devnet suites (node + pool + miner).",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=DEFAULT_CONFIG,
        help="Path to devnet-manager.json",
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    def add_suite_component_args(subparser: argparse.ArgumentParser) -> None:
        subparser.add_argument(
            "--suite",
            action="append",
            dest="suites",
            help="Suite to operate on (repeat for multiple). Defaults to all suites.",
        )
        subparser.add_argument(
            "--component",
            action="append",
            dest="components",
            choices=COMPONENT_ORDER,
            help="Component to operate on (defaults to node,pool,miner).",
        )

    update_parser = subparsers.add_parser("update", help="Rebuild and copy artifacts into suites.")
    add_suite_component_args(update_parser)
    update_parser.add_argument("--build", action="store_true", help="Run mvn clean package before copying.")
    update_parser.add_argument("--xdagj-jar", type=Path, help="Path to XDAGJ jar to copy into node folders.")
    update_parser.add_argument("--pool-jar", type=Path, help="Path to xdagj-pool jar to copy into pool folders.")
    update_parser.add_argument("--miner-jar", type=Path, help="Path to xdagj-miner jar to copy into miner folders.")

    start_parser = subparsers.add_parser("start", help="Start node/pool/miner processes.")
    add_suite_component_args(start_parser)

    stop_parser = subparsers.add_parser("stop", help="Stop node/pool/miner processes.")
    add_suite_component_args(stop_parser)

    status_parser = subparsers.add_parser("status", help="Show current process + health status.")
    add_suite_component_args(status_parser)

    check_parser = subparsers.add_parser("check", help="Compare chain state across node HTTP APIs.")
    check_parser.add_argument(
        "--suite",
        action="append",
        dest="suites",
        help="Subset of suites to verify (defaults to all).",
    )

    return parser.parse_args()


def main() -> None:
    args = parse_args()
    manager = DevnetManager(args.config)

    if args.command == "update":
        manager.update(
            suites=args.suites or (),
            components=args.components or (),
            build=args.build,
            node_jar=args.xdagj_jar,
            pool_jar=args.pool_jar,
            miner_jar=args.miner_jar,
        )
    elif args.command == "start":
        manager.start(args.suites or (), args.components or ())
    elif args.command == "stop":
        manager.stop(args.suites or (), args.components or ())
    elif args.command == "status":
        manager.status(args.suites or (), args.components or ())
    elif args.command == "check":
        manager.check_consistency(args.suites or ())
    else:
        raise SystemExit(f"Unknown command: {args.command}")


if __name__ == "__main__":
    main()
