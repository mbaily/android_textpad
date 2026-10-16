from base64 import b64decode, b64encode
import binascii
from contextlib import closing, suppress
from datetime import timedelta
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import secrets
import sqlite3
import threading
import time
from urllib.parse import urlsplit

from flask import Flask, jsonify, redirect, render_template, request, send_file, session
from flask_sock import Sock
from simple_websocket import ConnectionClosed
from werkzeug.exceptions import HTTPException
from werkzeug.middleware.proxy_fix import ProxyFix

from .codec import MAX_BYTES, InvalidText, decode, edited
from .events import ChangeSignal
from .store import NotFound, RequestConflict, Store, filename, identifier


def read_secret(path):
    value = Path(path).read_text().strip()
    if len(value) < 24:
        raise RuntimeError("Server credentials must contain at least 24 characters.")
    return value


def same(left, right):
    return isinstance(left, str) and isinstance(right, str) and hmac.compare_digest(left.encode(), right.encode())


def create_app(test_config=None):
    app = Flask(__name__, static_url_path="/editor/static")
    app.config.from_mapping(
        DATABASE=os.environ.get("TEXTPAD_DATABASE", "/opt/android_textpad/data/textpad.sqlite3"),
        PUBLIC_URL=os.environ.get("TEXTPAD_PUBLIC_URL", "https://git23069.hopto.org/android_textpad").rstrip("/"),
        MAX_CONTENT_LENGTH=32 * 1024 * 1024,
        SESSION_COOKIE_NAME="textpad_session", SESSION_COOKIE_SECURE=True,
        SESSION_COOKIE_HTTPONLY=True, SESSION_COOKIE_SAMESITE="Strict",
        PERMANENT_SESSION_LIFETIME=timedelta(days=30),
        SOCK_SERVER_OPTIONS={"ping_interval":25, "max_message_size":4096},
    )
    if test_config is not None:
        app.config.update(test_config)
    else:
        app.config["API_TOKEN"] = read_secret(os.environ.get("TEXTPAD_TOKEN_FILE", "/etc/android-textpad/api-token"))
        app.config["SECRET_KEY"] = read_secret(os.environ.get("TEXTPAD_SESSION_FILE", "/etc/android-textpad/session-key"))
        password_file = os.environ.get("TEXTPAD_BROWSER_PASSWORD_FILE")
        if password_file:
            app.config["BROWSER_PASSWORD"] = read_secret(password_file)
    url = urlsplit(app.config["PUBLIC_URL"])
    prefix = url.path.rstrip("/")
    app.config["SESSION_COOKIE_PATH"] = prefix + "/editor"
    app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1)
    changes = ChangeSignal()
    app.extensions["changes"] = changes
    store = Store(app.config["DATABASE"], on_commit=changes.publish)
    app.extensions["store"] = store
    api_digest = hashlib.sha256(app.config["API_TOKEN"].encode()).hexdigest()
    browser_secret = app.config.get("BROWSER_PASSWORD", app.config["API_TOKEN"])
    browser_digest = hashlib.sha256(browser_secret.encode()).hexdigest()
    auth_value = hmac.new(app.secret_key.encode(), browser_digest.encode(), hashlib.sha256).hexdigest()

    def public(path):
        return prefix + path

    def signed_in():
        return same(session.get("auth"), auth_value)

    def csrf_token():
        if "csrf" not in session:
            session["csrf"] = secrets.token_hex(24)
        return session["csrf"]

    def check_csrf():
        origin = request.headers.get("Origin")
        expected = f"{url.scheme}://{url.netloc}"
        if origin is not None and origin != expected:
            return False
        candidate = request.headers.get("X-CSRF-Token") or request.form.get("csrf")
        return same(candidate, session.get("csrf"))

    @app.before_request
    def authenticate():
        if request.path.startswith("/api/"):
            authorization = request.headers.get("Authorization", "")
            token = authorization[7:] if authorization.startswith("Bearer ") else ""
            if not token or not hmac.compare_digest(hashlib.sha256(token.encode()).hexdigest(), api_digest):
                return jsonify(error="A valid API token is required."), 401
        if request.path.startswith("/editor/api/"):
            if not signed_in():
                return jsonify(error="Your session has expired. Sign in again without closing your unsaved tabs."), 401
            if request.path == "/editor/api/events" and request.headers.get("Origin") != f"{url.scheme}://{url.netloc}":
                return jsonify(error="The editor connection must come from this site."), 403
            if request.method not in {"GET", "HEAD", "OPTIONS"} and not check_csrf():
                return jsonify(error="The page's sign-in state changed. Reload it after saving a copy of any unsaved edits."), 403

    @app.after_request
    def headers(response):
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "same-origin"
        response.headers["X-Frame-Options"] = "DENY"
        nonce = getattr(request, "csp_nonce", "")
        style_src = f"style-src 'self' 'nonce-{nonce}'" if nonce else "style-src 'self'"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; worker-src 'self'; manifest-src 'self'; "
            f"{style_src}; "
            f"connect-src 'self' {'wss' if url.scheme == 'https' else 'ws'}://{url.netloc}; "
            "img-src 'self' data:; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
        return response

    sockets = Sock(app)

    def change_events(ws):
        subscription = changes.subscribe()

        def send_changes():
            try:
                while subscription.wait():
                    ws.send(json.dumps({"type":"changed", "cursor":store.change_cursor()}))
            except (ConnectionClosed, OSError, sqlite3.Error):
                pass
            finally:
                with suppress(ConnectionClosed, OSError):
                    ws.close()

        sender = threading.Thread(target=send_changes, name="textpad-event-sender", daemon=True)
        try:
            ws.send(json.dumps({"type":"ready", "cursor":store.change_cursor()}))
            sender.start()
            deadline = time.monotonic() + 12 * 60 * 60
            while time.monotonic() < deadline:
                # Connection close or the existing session lifetime ends this wait;
                # commit notifications wake the sender independently, without a timer.
                ws.receive(timeout=max(0, deadline-time.monotonic()))
        except ConnectionClosed:
            pass
        finally:
            subscription.close()
            with suppress(ConnectionClosed, OSError):
                ws.close()
            if sender.ident is not None:
                sender.join(timeout=2)

    sockets.route("/api/v1/events")(change_events)
    sockets.route("/editor/api/events", endpoint="browser_events")(change_events)

    @app.errorhandler(HTTPException)
    def http_error(error):
        return jsonify(error=error.description), error.code

    @app.errorhandler(ValueError)
    def bad_input(error):
        return jsonify(error=str(error)), 400

    @app.errorhandler(NotFound)
    def not_found(error):
        return jsonify(error=str(error)), 404

    @app.errorhandler(RequestConflict)
    def request_conflict(error):
        return jsonify(error=str(error)), 409

    @app.errorhandler(sqlite3.Error)
    def storage_failure(error):
        app.logger.error("SQLite operation failed: %s", type(error).__name__)
        return jsonify(error="Server storage is temporarily unavailable. Your save was not acknowledged; keep your edits and retry."), 503

    @app.get("/health")
    def health():
        with closing(store.connect()) as db:
            db.execute("SELECT 1").fetchone()
        return jsonify(status="ok", service="textpad", schema=1)

    @app.get("/api/v1/documents")
    def api_documents():
        return jsonify(documents=store.list())

    def binary_document(document_id, revision=None):
        metadata, raw = store.get(document_id, revision)
        return jsonify(**metadata, content_base64=b64encode(raw).decode("ascii"))

    @app.get("/api/v1/documents/<document_id>")
    def api_document(document_id):
        return binary_document(document_id)

    @app.put("/api/v1/documents/<document_id>")
    def api_save(document_id):
        payload = request.get_json()
        if not isinstance(payload, dict) or not isinstance(payload.get("content_base64"), str):
            raise ValueError("Send a JSON document with Base64-encoded file contents.")
        try:
            raw = b64decode(payload["content_base64"], validate=True)
        except (binascii.Error, ValueError) as error:
            raise ValueError("Invalid Base64 file contents.") from error
        return jsonify(store.put(document_id, payload.get("name"), raw, payload.get("base_revision"),
                                 payload.get("request_id"), payload.get("encoding")))

    @app.get("/api/v1/documents/<document_id>/revisions")
    def api_revisions(document_id):
        return jsonify(documents=store.revisions(document_id))

    @app.get("/api/v1/documents/<document_id>/revisions/<revision>")
    def api_revision(document_id, revision):
        return binary_document(document_id, revision)

    @app.post("/api/v1/documents/<document_id>/prune")
    def api_prune(document_id):
        removed = store.prune(document_id)
        return jsonify(removed=removed, documents=store.revisions(document_id))

    @app.get("/editor")
    def editor_redirect():
        return redirect(public("/editor/"), code=302)

    @app.route("/editor/login", methods=["GET", "POST"])
    def login():
        error = None
        if request.method == "POST":
            if not check_csrf():
                return render_login("The sign-in form expired. Please try again."), 403
            address = hashlib.sha256((request.remote_addr or "unknown").encode()).hexdigest()
            now = time.time()
            with closing(store.connect()) as db:
                attempt = db.execute("SELECT failures,started FROM login_attempts WHERE address=?", (address,)).fetchone()
                if attempt and attempt[0] >= 10 and now - attempt[1] < 600:
                    return render_login("Too many attempts. Try again in 10 minutes."), 429
                candidate = request.form.get("credential", "")
                if hmac.compare_digest(hashlib.sha256(candidate.encode()).hexdigest(), browser_digest):
                    db.execute("DELETE FROM login_attempts WHERE address=?", (address,))
                    session.clear()
                    session["auth"] = auth_value
                    session["csrf"] = secrets.token_hex(24)
                    session.permanent = True
                    return redirect(public("/editor/"), code=303)
                failures = attempt[0] + 1 if attempt and now - attempt[1] < 600 else 1
                started = attempt[1] if attempt and now - attempt[1] < 600 else now
                db.execute("INSERT OR REPLACE INTO login_attempts VALUES(?,?,?)", (address,failures,started))
            error = "The sign-in details were not accepted."
        return render_login(error), 401 if error else 200

    def render_login(error=None):
        request.csp_nonce = secrets.token_urlsafe(18)
        return render_template("login.html", prefix=prefix, csrf=csrf_token(), error=error,
                               separate_password="BROWSER_PASSWORD" in app.config)

    @app.get("/editor/")
    def editor():
        if not signed_in():
            return redirect(public("/editor/login"), code=303)
        request.csp_nonce = secrets.token_urlsafe(18)
        return render_template("editor.html", prefix=prefix, csrf=csrf_token(), nonce=request.csp_nonce)

    @app.get("/editor/manifest.webmanifest")
    def editor_manifest():
        manifest = {
            "name": "Textpad",
            "short_name": "Textpad",
            "description": "Synced plain-text editor",
            "start_url": public("/editor/"),
            "scope": public("/editor/"),
            "display": "standalone",
            "background_color": "#101313",
            "theme_color": "#101313",
            "icons": [
                {"src": public("/editor/static/apple-touch-icon.png"), "sizes": "180x180", "type": "image/png"},
                {"src": public("/editor/static/icon-192.png"), "sizes": "192x192", "type": "image/png", "purpose": "any maskable"},
                {"src": public("/editor/static/icon-512.png"), "sizes": "512x512", "type": "image/png", "purpose": "any maskable"},
                {"src": public("/editor/static/favicon.svg"), "sizes": "any", "type": "image/svg+xml"},
            ],
        }
        return app.response_class(json.dumps(manifest), mimetype="application/manifest+json")

    @app.get("/editor/sw.js")
    def editor_service_worker():
        sw_code = (Path(app.static_folder) / "sw.js").read_text(encoding="utf-8")
        response = app.response_class(sw_code, mimetype="application/javascript")
        response.headers["Service-Worker-Allowed"] = public("/editor/")
        return response

    @app.post("/editor/api/logout")
    def logout():
        session.clear()
        return jsonify(ok=True)

    @app.get("/editor/api/connection")
    def connection():
        return jsonify(endpoint=app.config["PUBLIC_URL"], token=app.config["API_TOKEN"])

    @app.get("/editor/api/documents")
    def browser_documents():
        return jsonify(documents=store.list())

    def text_document(document_id, revision=None):
        metadata, raw = store.get(document_id, revision)
        decoded = decode(raw, metadata["encoding"])
        return jsonify(**metadata, text=decoded.text, bom=decoded.bom, line_endings=decoded.ending_label)

    @app.get("/editor/api/documents/<document_id>")
    def browser_document(document_id):
        return text_document(document_id)

    @app.put("/editor/api/documents/<document_id>")
    def browser_save(document_id):
        payload = request.get_json()
        if not isinstance(payload, dict):
            raise ValueError("Send a JSON document.")
        name = filename(payload.get("name"))
        base_revision = payload.get("base_revision")
        if base_revision is not None:
            metadata, raw = store.get(document_id, base_revision)
            base = decode(raw, metadata["encoding"])
        else:
            try:
                store.get(document_id)
            except NotFound:
                base = decode(b"")
            else:
                if not store.has_receipt(document_id, payload.get("request_id")):
                    raise RequestConflict("This document already exists. Reopen it before saving.")
                base = decode(b"")
        raw = edited(base, payload.get("text"), payload.get("encoding"), payload.get("bom"))
        response = store.put(document_id,name,raw,base_revision,payload.get("request_id"),payload.get("encoding") or base.encoding)
        formatted = decode(raw, response["encoding"])
        response["bom"] = formatted.bom
        response["line_endings"] = formatted.ending_label
        return jsonify(response)

    @app.get("/editor/api/documents/<document_id>/revisions")
    def browser_revisions(document_id):
        return jsonify(documents=store.revisions(document_id))

    @app.get("/editor/api/documents/<document_id>/revisions/<revision>")
    def browser_revision(document_id, revision):
        return text_document(document_id, revision)

    @app.post("/editor/api/documents/<document_id>/restore")
    def browser_restore(document_id):
        payload = request.get_json()
        if not isinstance(payload, dict):
            raise ValueError("Send a JSON revision to restore.")
        metadata, raw = store.get(document_id, str(payload.get("revision")))
        return jsonify(store.put(document_id,metadata["name"],raw,payload.get("base_revision"),
                                 payload.get("request_id"),metadata["encoding"]))

    @app.post("/editor/api/documents/<document_id>/prune")
    def browser_prune(document_id):
        removed = store.prune(document_id)
        return jsonify(removed=removed, documents=store.revisions(document_id))

    @app.get("/editor/api/documents/<document_id>/download")
    def download(document_id):
        metadata, raw = store.get(document_id, request.args.get("revision"))
        return send_file(io.BytesIO(raw), mimetype="application/octet-stream", as_attachment=True,
                         download_name=metadata["name"], max_age=0)

    return app
