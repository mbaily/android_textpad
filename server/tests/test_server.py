from base64 import b64encode, b64decode
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import hashlib
import re
import sqlite3
import tempfile
import unittest
from pathlib import Path

from textpad_server import create_app
from textpad_server.codec import MAX_BYTES, InvalidText, decode, edited
from textpad_server.store import NotFound, RequestConflict, Store


class CodecTests(unittest.TestCase):
    def test_unicode_bom_and_mixed_newlines_roundtrip(self):
        text = "hello\r\n😀 e\u0301 العربية\n漢字\rfinal"
        for encoding, bom in [("utf-8",b""),("utf-8",b"\xef\xbb\xbf"),("utf-16-le",b"\xff\xfe"),("utf-16-be",b"\xfe\xff")]:
            raw = bom + text.encode(encoding)
            base = decode(raw)
            self.assertEqual(raw, edited(base,base.text))
            self.assertEqual(bom + text.replace("hello","HELLO").encode(encoding), edited(base,base.text.replace("hello","HELLO")))

    def test_inserted_lines_preserve_original_suffix_terminators(self):
        base = decode(b"a\r\nb\nc\r")
        result = edited(base,"a\nnew\nb\nc\n")
        self.assertEqual("a\nnew\nb\nc\n",decode(result).text)
        self.assertTrue(result.endswith(b"b\nc\r"))

    def test_ascii_and_incomplete_unicode_are_rejected_without_replacement(self):
        with self.assertRaises(InvalidText): edited(decode(b"hello"),"café","ascii")
        with self.assertRaises(InvalidText): edited(decode(b""),"\ud800")
        with self.assertRaises(InvalidText): decode(b"\xc3(")
        with self.assertRaises(InvalidText): decode(b"\x00\x01\x03")
        with self.assertRaises(InvalidText): decode(b"\xff\xfe\x00\x00")

    def test_size_boundary_and_explicit_bomless_utf16(self):
        self.assertEqual(MAX_BYTES,len(decode(b"a"*MAX_BYTES).raw))
        with self.assertRaises(InvalidText): decode(b"a"*(MAX_BYTES+1))
        raw="漢字 Ελληνικά".encode("utf-16-le")
        self.assertEqual(raw,edited(decode(raw,"utf-16-le"),"漢字 Ελληνικά"))


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.temp.name)/"db.sqlite3")
    def tearDown(self): self.temp.cleanup()
    def put(self, text, request="request-1", base=None, doc="test-file"):
        return self.store.put(doc,"notes.txt",text.encode(),base,request)

    def test_stale_writes_win_and_displaced_contents_remain(self):
        first=self.put("first")
        second=self.put("second","request-2",first["revision"])
        third=self.put("offline version","request-3",first["revision"])
        self.assertTrue(third["replaced_newer_revision"])
        self.assertEqual(b"offline version",self.store.get("test-file")[1])
        self.assertEqual(b"second",self.store.get("test-file",second["revision"])[1])
        self.assertEqual(3,len(self.store.revisions("test-file")))

    def test_receipt_retry_never_reorders_an_older_write(self):
        first=self.put("first"); self.put("newer","request-2",first["revision"])
        self.assertEqual(first,self.put("first"))
        self.assertEqual(b"newer",self.store.get("test-file")[1])
        self.assertEqual(2,len(self.store.revisions("test-file")))
        with self.assertRaises(RequestConflict): self.put("different")

    def test_concurrent_writes_have_one_monotonic_acceptance_order(self):
        first=self.put("first")
        with ThreadPoolExecutor(max_workers=8) as workers:
            results=list(workers.map(lambda n:self.put(f"version-{n}",f"parallel-{n}",first["revision"]),range(16)))
        revisions=[int(result["revision"]) for result in results]
        self.assertEqual(16,len(set(revisions)))
        self.assertEqual(str(max(revisions)),self.store.get("test-file")[0]["revision"])
        self.assertEqual(17,len(self.store.revisions("test-file")))

    def test_backup_is_consistent_and_receipts_survive_history_removal(self):
        first=self.put("first"); self.put("second","request-2",first["revision"])
        destination=Path(self.temp.name)/"backup.sqlite3"; self.store.backup(destination)
        backup=Store(destination);self.assertEqual(b"second",backup.get("test-file")[1])
        self.store.remove_revision("test-file",first["revision"])
        self.assertEqual(first,self.put("first"))
        with self.assertRaises(NotFound): self.store.get("test-file",first["revision"])
        with self.assertRaises(ValueError): self.store.remove_revision("test-file",self.store.get("test-file")[0]["revision"])

    def test_metadata_listing_does_not_return_contents(self):
        self.put("private text")
        self.assertNotIn("content",self.store.list()[0])
        self.assertNotIn("content",self.store.revisions("test-file")[0])

    def test_hybrid_pruning_thins_old_revisions_exponentially_and_protects_conflicts(self):
        from datetime import datetime, timedelta, timezone
        now = datetime(2026, 10, 16, 12, 0, 0, tzinfo=timezone.utc)
        base_rev = None
        revs = []
        for i in range(30):
            res = self.put(f"content-{i}", f"req-{i}", base_rev)
            revs.append(res)
            base_rev = res["revision"]
        # Create one conflict revision (displaced by an offline write based on an older revision)
        conflict_victim = revs[5]["revision"]
        with closing(self.store.connect()) as db:
            # Backdate all 30 revisions to 5 days ago within the same hour so time-bucketing groups them together,
            # except make revision 5 a displaced conflict victim in receipts.
            old_iso = (now - timedelta(days=5)).isoformat(timespec="microseconds")
            db.execute("UPDATE revisions SET created_at=?", (old_iso,))
            conflict_receipt = {
                "id": "test-file",
                "name": "notes.txt",
                "revision": revs[6]["revision"],
                "previous_revision": conflict_victim,
                "replaced_newer_revision": True,
                "updated_at": (now - timedelta(days=2)).isoformat(timespec="microseconds"),
            }
            db.execute(
                "UPDATE receipts SET response=? WHERE document_id='test-file' AND request_id='req-6'",
                (sqlite3. Binary(b"") if False else __import__("json").dumps(conflict_receipt),),
            )
            db.commit()
        removed = self.store.prune("test-file", now=now.timestamp())
        self.assertGreaterEqual(removed, 9)
        remaining = [r["revision"] for r in self.store.revisions("test-file")]
        # Most recent 10 revisions are all kept
        for r in revs[-10:]:
            self.assertIn(r["revision"], remaining)
        # Very first revision is always kept
        self.assertIn(revs[0]["revision"], remaining)
        # Displaced conflict revision is protected
        self.assertIn(conflict_victim, remaining)
        # Receipts survive pruning
        for i in range(30):
            self.assertTrue(self.store.has_receipt("test-file", f"req-{i}"))


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.token="test-api-token-with-at-least-32-characters"
        self.app=create_app({"TESTING":True,"DATABASE":str(Path(self.temp.name)/"db.sqlite3"),"API_TOKEN":self.token,
                             "SECRET_KEY":"test-session-secret-with-32-characters","PUBLIC_URL":"http://localhost","SESSION_COOKIE_SECURE":False})
        self.client=self.app.test_client();self.headers={"Authorization":"Bearer "+self.token}
    def tearDown(self): self.temp.cleanup()
    def upload(self, raw=b"first", request="request-1", base=None, doc="test-file"):
        return self.client.put("/api/v1/documents/"+doc,headers=self.headers,
                json={"name":"notes.txt","content_base64":b64encode(raw).decode(),"request_id":request,"base_revision":base})
    def login(self):
        page=self.client.get("/editor/login")
        token=re.search(rb'name="csrf" value="([a-f0-9]+)"',page.data).group(1).decode()
        response=self.client.post("/editor/login",data={"csrf":token,"credential":self.token})
        self.assertEqual(303,response.status_code)
        with self.client.session_transaction(path="/editor/") as session: self.csrf=session["csrf"]
    def browser_put(self, doc, payload):
        return self.client.put("/editor/api/documents/"+doc,headers={"X-CSRF-Token":self.csrf},json=payload)

    def test_sync_requires_token_and_browser_session_cannot_replace_it(self):
        self.assertEqual(401,self.client.get("/api/v1/documents").status_code)
        self.login()
        self.assertEqual(401,self.client.get("/api/v1/documents").status_code)
        self.assertEqual(200,self.client.get("/api/v1/documents",headers=self.headers).status_code)

    def test_api_roundtrip_revisions_and_old_request_replay(self):
        raw=b"\xef\xbb\xbf"+"😀 café\r\nالعربية\n".encode()
        first=self.upload(raw).json
        second=self.upload(b"second","request-2",first["revision"]).json
        self.assertEqual(first,self.upload(raw).json)
        current=self.client.get("/api/v1/documents/test-file",headers=self.headers).json
        self.assertEqual(b"second",b64decode(current["content_base64"]))
        original=self.client.get("/api/v1/documents/test-file/revisions/"+first["revision"],headers=self.headers).json
        self.assertEqual(raw,b64decode(original["content_base64"]))
        self.assertEqual(2,len(self.client.get("/api/v1/documents/test-file/revisions",headers=self.headers).json["documents"]))
        self.assertGreater(int(second["revision"]),int(first["revision"]))

    def test_browser_save_is_visible_to_android_and_preserves_format(self):
        raw=b"\xff\xfe"+"one\r\n😀 café\nالعربية\r".encode("utf-16-le")
        first=self.upload(raw).json;self.login()
        page=self.client.get("/editor/api/documents/test-file").json
        payload={"name":"notes.txt","text":page["text"].replace("one","ONE"),"base_revision":first["revision"],"request_id":"browser-1"}
        saved=self.browser_put("test-file",payload)
        self.assertEqual(200,saved.status_code,saved.data)
        updated=self.client.get("/api/v1/documents/test-file",headers=self.headers).json
        self.assertEqual(b"\xff\xfe"+"ONE\r\n😀 café\nالعربية\r".encode("utf-16-le"),b64decode(updated["content_base64"]))
        self.assertEqual(saved.json["revision"],updated["revision"])

    def test_browser_new_file_retry_is_idempotent_after_lost_receipt(self):
        self.login();payload={"name":"new.txt","text":"new text","base_revision":None,"request_id":"browser-new"}
        first=self.browser_put("browser-new-file",payload)
        second=self.browser_put("browser-new-file",payload)
        self.assertEqual(200,first.status_code,first.data);self.assertEqual(first.json,second.json)
        payload["text"]="different"
        self.assertEqual(409,self.browser_put("browser-new-file",payload).status_code)

    def test_browser_auth_and_csrf_are_required(self):
        self.assertEqual(401,self.client.get("/editor/api/documents").status_code)
        self.assertEqual(303,self.client.get("/editor/").status_code)
        self.login()
        payload={"name":"notes.txt","text":"hello","base_revision":None,"request_id":"browser-new"}
        self.assertEqual(403,self.client.put("/editor/api/documents/file-1",json=payload).status_code)
        self.assertEqual(403,self.client.put("/editor/api/documents/file-1",json=payload,headers={"X-CSRF-Token":self.csrf,"Origin":"https://other.example"}).status_code)
        self.assertEqual(200,self.browser_put("file-1",payload).status_code)

    def test_restore_and_download_keep_exact_original_bytes(self):
        raw="first\r\n😀".encode();first=self.upload(raw).json;second=self.upload(b"second","request-2",first["revision"]).json
        self.login();restored=self.client.post("/editor/api/documents/test-file/restore",headers={"X-CSRF-Token":self.csrf},
                json={"revision":first["revision"],"base_revision":second["revision"],"request_id":"restore-1"})
        self.assertEqual(200,restored.status_code)
        download=self.client.get("/editor/api/documents/test-file/download")
        self.assertEqual(raw,download.data);self.assertIn("attachment",download.headers["Content-Disposition"])
        self.assertEqual(3,len(self.app.extensions["store"].revisions("test-file")))
        prune_api=self.client.post("/api/v1/documents/test-file/prune",headers=self.headers)
        self.assertEqual(200,prune_api.status_code)
        self.assertEqual(0,prune_api.json["removed"])
        prune_web=self.client.post("/editor/api/documents/test-file/prune",headers={"X-CSRF-Token":self.csrf})
        self.assertEqual(200,prune_web.status_code)
        self.assertEqual(3,len(prune_web.json["documents"]))

    def test_limits_binary_invalid_base64_and_names(self):
        self.assertEqual(400,self.upload(b"a"*(MAX_BYTES+1)).status_code)
        self.assertEqual(400,self.upload(b"\0\1\2").status_code)
        bad={"name":"notes.txt","content_base64":"not-base64!","request_id":"bad-1"}
        self.assertEqual(400,self.client.put("/api/v1/documents/file-1",headers=self.headers,json=bad).status_code)
        bad.update(name="../escape.txt",content_base64="")
        self.assertEqual(400,self.client.put("/api/v1/documents/file-1",headers=self.headers,json=bad).status_code)
        self.assertEqual([],self.app.extensions["store"].list())

    def test_ten_mib_is_supported(self):
        raw=b"a"*MAX_BYTES;response=self.upload(raw)
        self.assertEqual(200,response.status_code,response.data)
        self.assertEqual(hashlib.sha256(raw).hexdigest(),response.json["sha256"])

    def test_pwa_manifest_and_service_worker_assets(self):
        manifest=self.client.get("/editor/manifest.webmanifest")
        self.assertEqual(200,manifest.status_code)
        self.assertEqual("application/manifest+json",manifest.mimetype)
        data=manifest.json
        self.assertEqual("standalone",data["display"])
        self.assertEqual("/editor/",data["start_url"])
        self.assertTrue(any(icon["sizes"]=="180x180" for icon in data["icons"]))
        sw=self.client.get("/editor/sw.js")
        self.assertEqual(200,sw.status_code)
        self.assertIn("javascript",sw.mimetype)
        self.assertEqual("/editor/",sw.headers.get("Service-Worker-Allowed"))
        icon=self.client.get("/editor/static/apple-touch-icon.png")
        self.assertEqual(200,icon.status_code)
        self.assertTrue(icon.data.startswith(b"\x89PNG\r\n\x1a\n"))
        icon.close()
        self.login()
        editor_html=self.client.get("/editor/")
        self.assertEqual(200,editor_html.status_code)
        self.assertIn(b'rel="manifest"',editor_html.data)
        self.assertIn(b'name="apple-mobile-web-app-capable"',editor_html.data)


if __name__=="__main__": unittest.main()
