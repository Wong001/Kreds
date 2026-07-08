"""Hearth command line: init, run, pairing, demo, release tooling."""
from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from .node import HearthNode


def main(argv=None):
    p = argparse.ArgumentParser(prog="hearth",
                                description="Hearth vertical slice")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("init", help="create a new identity + first device")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--person", required=True)
    sp.add_argument("--device", required=True)

    sp = sub.add_parser("run", help="run one node daemon")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--gossip-port", type=int, required=True)
    sp.add_argument("--http-port", type=int, required=True)
    sp.add_argument("--interval", type=float, default=3.0)
    sp.add_argument("--tor", action="store_true",
                    help="run this node as a Tor onion service")

    sp = sub.add_parser("pair-request",
                        help="new device: create keys + print request")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--device", required=True)

    sp = sub.add_parser("pair-accept",
                        help="existing device: turn request into package")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--request-file", required=True)
    sp.add_argument("--package-file", required=True)

    sp = sub.add_parser("pair-install",
                        help="new device: install package")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--package-file", required=True)

    sp = sub.add_parser("serve",
                        help="run one node, bootstrapping first-run if no identity yet")
    sp.add_argument("--dir", required=True)
    sp.add_argument("--http-port", type=int, required=True)
    sp.add_argument("--gossip-port", type=int, default=0)
    sp.add_argument("--interval", type=float, default=3.0)
    sp.add_argument("--tor", action="store_true")

    dp = sub.add_parser("demo", help="run the four-node demo cast")
    dp.add_argument("--tor", action="store_true",
                    help="run the cast over Tor onion services")

    sp = sub.add_parser("app", help="launch the Kreds desktop app (frameless window)")
    sp.add_argument("--dir", default=None, help="data dir (default %%APPDATA%%/Kreds)")

    sp = sub.add_parser("release-build",
                        help="assemble web+core bundles + an unsigned update "
                             "manifest for the kreds_updater feed")
    sp.add_argument("--web", required=True, help="web asset directory to bundle (hearth/web)")
    sp.add_argument("--core", required=True,
                    help="PyInstaller payload dir to bundle "
                         "(dist/Kreds/versions/<version>)")
    sp.add_argument("--out", default="release",
                    help="output directory for bundles + manifest.json "
                         "(default: release/)")
    sp.add_argument("--version", default=None,
                    help="release version (default: CORE_VERSION)")
    sp.add_argument("--core-version", default=None,
                    help="core_version manifest field (default: --version)")
    sp.add_argument("--min-core-for-web", default=None,
                    help="min_core_for_web manifest field (default: \"0.0.0\", "
                         "i.e. this release's web bundle works with any "
                         "installed core -- pass this explicitly only when the "
                         "web bundle actually needs APIs from the new core)")
    sp.add_argument("--notes", default="", help="release notes")
    sp.add_argument("--released-at", default=None,
                    help="ISO8601 timestamp to stamp into the manifest; omitted "
                         "if not given (no wall-clock default, so release-build's "
                         "output stays deterministic/testable)")

    sp = sub.add_parser("release-sign",
                        help="sign a release manifest with the offline release "
                             "private key (see RELEASE.md step 4)")
    sp.add_argument("--manifest", required=True)
    sp.add_argument("--key", required=True,
                    help="path to the release private key hex file")

    sp = sub.add_parser("release-publish",
                        help="upload release/ assets to a kreds_updater GitHub "
                             "release (shells out to gh)")
    sp.add_argument("--version", required=True)
    sp.add_argument("--dir", default="release",
                    help="directory holding manifest.json/.sig + bundles "
                         "(default: release/)")
    sp.add_argument("--repo", default="wong001/kreds_updater")
    sp.add_argument("--dry-run", action="store_true",
                    help="print the gh command instead of running it")

    args = p.parse_args(argv)

    if args.cmd == "init":
        node = HearthNode.create(args.dir, args.person, args.device)
        print("identity: " + node.identity_pub)
        print("paper seed written to "
              + str(Path(args.dir) / "paper_seed.txt"))
        node.close()
    elif args.cmd == "run":
        from .runner import run_node
        asyncio.run(run_node(args.dir, args.gossip_port, args.http_port,
                             args.interval, tor=args.tor))
    elif args.cmd == "serve":
        from .runner import run_serve
        gossip_port = args.gossip_port
        if args.tor and gossip_port == 0:
            # run_node's tor branch binds gossip_port literally and
            # publishes THAT port on the onion service (it never reads
            # back the actual bound port the way the plain-TCP branch
            # does) -- 0 ("any free port") would publish an onion address
            # pointing at a port nothing is listening on. --gossip-port
            # defaults to 0 for the common plain-TCP case, where
            # sync.start's ephemeral port is used consistently either way;
            # only --tor needs a real, fixed port here.
            gossip_port = args.http_port + 1000
        asyncio.run(run_serve(args.dir, gossip_port, args.http_port,
                              args.interval, tor=args.tor))
    elif args.cmd == "pair-request":
        print(HearthNode.pair_request(args.dir, args.device))
    elif args.cmd == "pair-accept":
        node = HearthNode(args.dir)
        pkg = node.accept_pairing(
            Path(args.request_file).read_text())
        Path(args.package_file).write_text(pkg)
        print("package written to " + args.package_file)
        node.close()
    elif args.cmd == "pair-install":
        node = HearthNode.pair_install(
            args.dir, Path(args.package_file).read_text())
        print("device enrolled; identity: " + node.identity_pub)
        node.close()
    elif args.cmd == "demo":
        from .demo import demo
        try:
            asyncio.run(demo(tor=args.tor))
        except KeyboardInterrupt:
            print("demo stopped")
    elif args.cmd == "app":
        from .desktop import launch
        launch(args.dir)
    elif args.cmd == "release-build":
        from . import update as up
        version = args.version or up.CORE_VERSION
        core_version = args.core_version or version
        min_core_for_web = args.min_core_for_web or "0.0.0"
        out_dir = Path(args.out)
        out_dir.mkdir(parents=True, exist_ok=True)

        web_name = "web-" + version + ".zip"
        web_data, web_digest = up.build_web_bundle(args.web)
        (out_dir / web_name).write_bytes(web_data)

        core_name = "core-" + version + ".zip"
        core_data, core_digest = up.build_core_bundle(args.core)
        (out_dir / core_name).write_bytes(core_data)

        # kreds_updater release-asset URLs for tag v<version> -- the same
        # shape release-publish/RELEASE.md's `gh release create v<version>
        # ...` uploads these bundles to.
        base_url = ("https://github.com/wong001/kreds_updater/releases/"
                    "download/v" + version + "/")
        manifest = {
            "version": version,
            "core_version": core_version,
            "min_core_for_web": min_core_for_web,
            "web": {
                "url": base_url + web_name,
                "sha256": web_digest,
                "size": len(web_data),
            },
            "core": {
                "url": base_url + core_name,
                "sha256": core_digest,
                "size": len(core_data),
            },
            "notes": args.notes,
        }
        if args.released_at:
            manifest["released_at"] = args.released_at

        manifest_path = out_dir / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, sort_keys=True, indent=2))
        print("wrote " + str(out_dir / web_name))
        print("wrote " + str(out_dir / core_name))
        print("wrote " + str(manifest_path))
    elif args.cmd == "release-sign":
        from . import update as up
        manifest_path = Path(args.manifest)
        manifest_bytes = manifest_path.read_bytes()
        key_hex = Path(args.key).read_text().strip()
        sig = up.sign_manifest(manifest_bytes, key_hex)
        # update.check() always fetches a sibling literally named
        # "manifest.sig" next to the feed URL (hearth/update.py's
        # _sibling_url) -- with_suffix, not "<name>.sig" appended, so
        # manifest.json -> manifest.sig (not manifest.json.sig).
        sig_path = manifest_path.with_suffix(".sig")
        sig_path.write_bytes(sig)
        print("wrote " + str(sig_path))
    elif args.cmd == "release-publish":
        rel_dir = Path(args.dir)
        version = args.version
        assets = [rel_dir / "manifest.json", rel_dir / "manifest.sig",
                 rel_dir / ("web-" + version + ".zip"),
                 rel_dir / ("core-" + version + ".zip")]
        missing = [str(a) for a in assets if not a.exists()]
        if missing:
            raise SystemExit("release-publish: missing asset(s), run "
                             "release-build/-sign first: " + ", ".join(missing))
        cmd = (["gh", "release", "create", "v" + version]
              + [str(a) for a in assets] + ["--repo", args.repo])
        print(" ".join(cmd))
        if not args.dry_run:
            import subprocess
            subprocess.run(cmd, check=True)
