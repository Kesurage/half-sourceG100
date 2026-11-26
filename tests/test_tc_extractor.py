#!/usr/bin/env python3
"""Tests for tools/tc_extractor.py — run with: python3 tests/test_tc_extractor.py"""

import json
import os
import shutil
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
import tc_extractor as tc


def make_vpk(path: str, version: int = 1) -> None:
    """Minimal valid VPK: header + empty tree."""
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x55AA1234, version, 0))  # sig, ver, treeSize=0


class Fixture:
    """Builds a standalone-TC-shaped directory tree."""

    def __init__(self, root: str):
        self.root = root
        self.game = os.path.join(root, "MyTC")
        for d in ("maps", "materials", "models", "sound", "resource", "scripts"):
            os.makedirs(os.path.join(self.game, d))
        # content files
        with open(os.path.join(self.game, "maps", "tc_map01.bsp"), "wb") as f:
            f.write(b"fakebsp")
        with open(os.path.join(self.game, "materials", "tc_mat.vmt"), "w") as f:
            f.write('"VertexLitGeneric"{}')
        # standalone TC artifacts
        with open(os.path.join(self.game, "hl2.exe"), "wb") as f:
            f.write(b"MZ")
        os.makedirs(os.path.join(self.game, "bin"))
        for dll in ("engine.dll", "vphysics.dll", "shaderapidx9.dll",
                    "client.dll", "server.dll"):
            with open(os.path.join(self.game, "bin", dll), "wb") as f:
                f.write(b"MZ")
        # redists / installers / junk
        os.makedirs(os.path.join(self.game, "directx"))
        with open(os.path.join(self.game, "directx", "dxsetup.exe"), "wb") as f:
            f.write(b"MZ")
        with open(os.path.join(self.game, "install.bat"), "w") as f:
            f.write("@echo off")
        # gameinfo
        with open(os.path.join(self.game, "gameinfo.txt"), "w") as f:
            f.write('"GameInfo"\n{\n\tgame "My Big TC"\n\ttitle "MYTC"\n'
                    '\tSteamAppId 218\n\tfilesystem\n\t{\n\t\tSteamAppId 218\n'
                    '\t\tSearchPaths\n\t\t{\n\t\t\tGame "|gameinfo_path|."\n'
                    '\t\t\tGame "c:\\Program Files\\mytc"\n\t\t}\n\t}\n}\n')


class TestClassifier(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="hsg100-tc-")
        self.addCleanup(shutil.rmtree, self.tmp)

    def test_standalone_tc_detected(self):
        fx = Fixture(self.tmp)
        info = tc.classify(fx.root)
        self.assertTrue(info["standalone_tc"])
        self.assertEqual(info["class"], "STANDALONE_TC")
        self.assertEqual(info["steam_appid"], 218)
        self.assertEqual(info["title"], "MYTC")

    def test_content_only(self):
        root = os.path.join(self.tmp, "mod1")
        os.makedirs(os.path.join(root, "maps"))
        with open(os.path.join(root, "gameinfo.txt"), "w") as f:
            f.write('"GameInfo"\n{\n\tgame "X"\n\ttitle "X"\n}\n')
        info = tc.classify(root)
        self.assertEqual(info["class"], "CONTENT_ONLY")

    def test_dll_only(self):
        root = os.path.join(self.tmp, "mod2")
        os.makedirs(os.path.join(root, "bin"))
        os.makedirs(os.path.join(root, "maps"))
        with open(os.path.join(root, "bin", "client.dll"), "wb") as f:
            f.write(b"MZ")
        with open(os.path.join(root, "gameinfo.txt"), "w") as f:
            f.write('"GameInfo"\n{\n\tgame "Y"\n}\n')
        info = tc.classify(root)
        self.assertEqual(info["class"], "DLL_ONLY")
        self.assertTrue(info["has_windows_dlls"])

    def test_source_mod(self):
        root = os.path.join(self.tmp, "mod3")
        os.makedirs(os.path.join(root, "src", "game"))
        os.makedirs(os.path.join(root, "maps"))
        with open(os.path.join(root, "src", "game", "game.vcxproj"), "w") as f:
            f.write("<Project/>")
        with open(os.path.join(root, "gameinfo.txt"), "w") as f:
            f.write('"GameInfo"\n{\n\tgame "Z"\n}\n')
        info = tc.classify(root)
        self.assertEqual(info["class"], "SOURCE_MOD")

    def test_broken_gameinfo(self):
        root = os.path.join(self.tmp, "mod4")
        os.makedirs(root)
        with open(os.path.join(root, "gameinfo.txt"), "w") as f:
            f.write('"GameInfo" { game "x" ')  # unterminated
        info = tc.classify(root)
        # parse is lenient in the python twin; unterminated brace still yields a node
        self.assertIn(info["class"], ("CONTENT_ONLY", "BROKEN_GAMEINFO"))

    def test_empty(self):
        root = os.path.join(self.tmp, "mod5")
        os.makedirs(os.path.join(root, "nothing"))
        info = tc.classify(root)
        self.assertEqual(info["class"], "EMPTY")


class TestKeyValues(unittest.TestCase):
    def test_parse_nested_and_comments(self):
        text = ('"GameInfo"\n{\n\tgame "A"\n // c\n /* b */\n'
                '\tfs\n\t{\n\t\tk v\n\t}\n}\n')
        kv = tc.parse_keyvalues(text)
        self.assertEqual(kv["GameInfo"]["game"], "A")
        self.assertEqual(kv["GameInfo"]["fs"]["k"], "v")

    def test_escaped_quotes(self):
        kv = tc.parse_keyvalues('"k" "say \\"hi\\""\n')
        self.assertEqual(kv["k"], 'say "hi"')


class TestVpkCheck(unittest.TestCase):
    def test_valid_v1(self):
        with tempfile.NamedTemporaryFile(suffix=".vpk", delete=False) as f:
            make_vpk(f.name, 1)
            self.addCleanup(os.unlink, f.name)
            ok, ver = tc.vpk_signature(f.name)
            self.assertTrue(ok)
            self.assertEqual(ver, 1)

    def test_invalid(self):
        with tempfile.NamedTemporaryFile(suffix=".vpk", delete=False) as f:
            f.write(b"garbage!")
            self.addCleanup(os.unlink, f.name)
            ok, _ = tc.vpk_signature(f.name)
            self.assertFalse(ok)


class TestExtract(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="hsg100-extract-")
        self.addCleanup(shutil.rmtree, self.tmp)
        self.fx = Fixture(self.tmp)
        self.out = os.path.join(self.tmp, "out")

    def test_extraction_shape(self):
        report = tc.extract(self.fx.root, self.out)
        dest = report["dest"]

        self.assertEqual(report["class"], "STANDALONE_TC")
        # content dirs kept
        for d in ("maps", "materials", "models", "sound", "resource", "scripts"):
            self.assertTrue(os.path.isdir(os.path.join(dest, d)), f"{d} missing")
        self.assertTrue(os.path.isfile(os.path.join(dest, "maps", "tc_map01.bsp")))
        # engine + code stripped
        self.assertFalse(os.path.exists(os.path.join(dest, "hl2.exe")))
        self.assertFalse(os.path.exists(os.path.join(dest, "bin")))
        self.assertFalse(os.path.exists(os.path.join(dest, "directx")))
        self.assertFalse(os.path.exists(os.path.join(dest, "install.bat")))
        # gameinfo rewritten
        self.assertTrue(os.path.isfile(os.path.join(dest, "gameinfo.txt")))
        self.assertTrue(os.path.isfile(os.path.join(dest, "gameinfo.txt.orig")))
        with open(os.path.join(dest, "gameinfo.txt")) as f:
            gi = f.read()
        self.assertIn('"220"', gi)              # re-parented to HL2
        self.assertIn("|MyTC|", gi)             # mod mounted first
        self.assertIn("|hl2|", gi)              # base fallback
        # manifest exists and is valid JSON
        with open(os.path.join(dest, "manifest.json")) as f:
            man = json.load(f)
        self.assertEqual(man["mod"], "MyTC")
        # stripped list mentions engine artifacts
        stripped = " ".join(report["stripped"])
        self.assertIn("hl2.exe", stripped)
        self.assertIn("bin", stripped)

    def test_dry_run_writes_nothing(self):
        report = tc.extract(self.fx.root, self.out, dry_run=True)
        self.assertTrue(report["dry_run"])
        self.assertFalse(os.path.exists(report["dest"]))
        self.assertGreater(len(report["kept"]), 0)

    def test_vpk_kept_and_validated(self):
        # add a valid vpk to the fixture
        make_vpk(os.path.join(self.fx.game, "tc_content_dir.vpk"), 2)
        report = tc.extract(self.fx.root, self.out)
        self.assertEqual(len(report["vpks"]), 1)
        self.assertEqual(report["vpks"][0]["version"], 2)
        self.assertTrue(os.path.isfile(os.path.join(report["dest"], "tc_content_dir.vpk")))
        # invalid vpk gets stripped with a warning
        with open(os.path.join(self.fx.game, "broken_dir.vpk"), "wb") as f:
            f.write(b"nope")
        report2 = tc.extract(self.fx.root, self.out + "2")
        joined = " ".join(report2["warnings"])
        self.assertIn("invalid VPK", joined)


class TestGameinfoGen(unittest.TestCase):
    def test_priority_order(self):
        gi = tc.generate_gameinfo("mymod", "My Mod")
        self.assertLess(gi.index("|mymod|"), gi.index("|hl2|"))
        self.assertIn('|hl2|hl2_textures_dir.vpk', gi)
        self.assertIn('"220"', gi)


if __name__ == "__main__":
    unittest.main(verbosity=2)
