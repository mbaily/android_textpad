"""HTTPS requests, bounded responses, and conditional idempotent saves."""
import base64
import binascii
from dataclasses import dataclass
import hashlib
import json
import re
import ssl
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPSHandler, HTTPRedirectHandler, ProxyHandler, Request, build_opener

from . import SyncError

MAX_BYTES = 10 * 1024 * 1024
MAX_RESPONSE = 16 * 1024 * 1024


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
        raise SyncError("Invalid server document or revision ID.")
    return value


def endpoint(value, *, local_test=False):
    parts = urlsplit(value)
    if ((parts.scheme != "https" and not (local_test and parts.scheme == "http" and parts.hostname == "127.0.0.1"))
            or not parts.hostname or parts.username is not None or parts.password is not None
            or parts.query or parts.fragment or any(c.isspace() for c in value)):
        raise SyncError("Use an HTTPS server URL without credentials, a query, or a fragment.")
    try:
        parts.port
    except ValueError as error:
        raise SyncError("Invalid server port.") from error
    return value.rstrip("/")


class ApiError(SyncError):
    def __init__(self, status, message, code=None):
        super().__init__(message)
        self.status, self.code = status, code


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


@dataclass(frozen=True)
class Document:
    id: str
    name: str
    revision: str
    sha256: str
    size: int
    content: bytes | None = None
    encoding: str | None = None

    @classmethod
    def parse(cls, data, content=False):
        try:
            doc_id, revision = identifier(data['id']), identifier(data['revision'])
            name, digest, size = data['name'], data['sha256'], data['size']
            if (not isinstance(name, str) or not name or len(name) > 255
                    or not isinstance(digest, str) or not re.fullmatch('[a-f0-9]{64}', digest)
                    or type(size) is not int or not 0 <= size <= MAX_BYTES):
                raise ValueError()
            raw = base64.b64decode(data['content_base64'], validate=True) if content else None
            if raw is not None and (len(raw) != size or hashlib.sha256(raw).hexdigest() != digest):
                raise SyncError("Downloaded bytes do not match the server's size/checksum.")
            return cls(doc_id, name, revision, digest, size, raw, data.get('encoding'))
        except (KeyError, TypeError, ValueError, binascii.Error) as error:
            raise SyncError("Invalid document returned by the server.") from error


class Api:
    def __init__(self, server, token, client_id, ca_file=None, timeout=20, *, insecure=False, local_test=False):
        self.server = endpoint(server, local_test=local_test)
        if not token or any(c.isspace() for c in token):
            raise SyncError("Set TEXTPAD_TOKEN or initialise this workspace with an API token.")
        self.token, self.client_id, self.timeout = token, identifier(client_id), timeout
        # Verification is enabled by default. A supplied CA is the trust store
        # for verified connections, including the deployment's private root CA.
        if insecure:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
        elif ca_file:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.load_verify_locations(cafile=str(ca_file))
        else:
            context = ssl.create_default_context()
        self.ssl_context = context
        proxies = ProxyHandler()
        self.proxies = proxies.proxies
        self.opener = build_opener(proxies, HTTPSHandler(context=context), NoRedirects())
        self.conditional_supported = False

    def request(self, method, path, body=None):
        headers = {'Authorization': 'Bearer ' + self.token, 'Accept': 'application/json',
                   'X-Textpad-Client': self.client_id}
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=True, separators=(',', ':')).encode()
            headers['Content-Type'] = 'application/json'
        req = Request(self.server + '/api/v1/' + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=self.timeout) as response:
                raw = response.read(MAX_RESPONSE + 1)
                if len(raw) > MAX_RESPONSE:
                    raise SyncError("The server response exceeds 16 MiB.")
                result = json.loads(raw)
                if not isinstance(result, dict):
                    raise ValueError()
                return result
        except HTTPError as error:
            code = None
            message = f"Server returned HTTP {error.code}."
            if error.code in (401, 403):
                message = "The server rejected the API token. Check TEXTPAD_TOKEN or the workspace token file."
            elif error.code == 409:
                try:
                    details = json.loads(error.read(8192))
                    code = details.get('code')
                except (ValueError, AttributeError):
                    pass
                message = "The server changed before this save; both versions are preserved. Run sync again."
            elif 300 <= error.code < 400:
                message = "Server redirects are refused. Configure the final HTTPS sync endpoint."
            error.close()
            raise ApiError(error.code, message, code) from None
        except (URLError, OSError) as error:
            if isinstance(getattr(error, 'reason', error), ssl.SSLCertVerificationError):
                raise SyncError('TLS certificate verification failed. Check the server address and trusted CA; '
                                'use init --ca-file ROOT_CA.pem for a private server CA.') from None
            raise SyncError(f"Could not reach the sync server ({type(error).__name__}); local files and pending saves are retained.") from None
        except (ValueError, UnicodeError) as error:
            raise SyncError("The server did not return valid JSON.") from error

    def capabilities(self):
        result = self.request('GET', 'capabilities')
        if result.get('conditional_put') is not True:
            raise SyncError("This server needs an update before the CLI can save safely (conditional writes unavailable).")
        self.conditional_supported = True

    def list(self):
        return self._documents(self.request('GET', 'documents'))

    @staticmethod
    def _documents(data):
        if not isinstance(data.get('documents'), list):
            raise SyncError("Invalid document list returned by the server.")
        return [Document.parse(d) for d in data['documents']]

    def get(self, doc_id, revision=None):
        path = 'documents/' + identifier(doc_id)
        if revision is not None:
            path += '/revisions/' + identifier(revision)
        result = Document.parse(self.request('GET', path), content=True)
        if result.id != doc_id or (revision is not None and result.revision != revision):
            raise SyncError("The server returned a different document/revision than requested.")
        return result

    def history(self, doc_id):
        return self._documents(self.request('GET', 'documents/' + identifier(doc_id) + '/revisions'))

    @staticmethod
    def _tags(value):
        if (not isinstance(value, list) or any(not isinstance(tag, str)
                or not re.fullmatch('[a-z_][a-z0-9_-]*', tag) for tag in value)):
            raise SyncError('Invalid hashtags returned by the server.')
        return value

    def errands(self, tags=None):
        result = self.request('GET', 'errands') if tags is None else self.request('PUT', 'errands', {'hashtags': tags})
        self._tags(result.get('hashtags'))
        if not isinstance(result.get('updated_at'), str):
            raise SyncError('Invalid errands settings returned by the server.')
        return {'hashtags': result['hashtags'], 'updated_at': result['updated_at']}

    def search_hashtags(self, tags, *, hide_completed=True, exclude_ids=()):
        result = self.request('POST', 'hashtags', {
            'tags': tags, 'hide_completed': hide_completed, 'exclude_ids': list(exclude_ids),
        })
        self._tags(result.get('all_hashtags'))
        try:
            if not isinstance(result['results'], list):
                raise ValueError()
            for match in result['results']:
                identifier(match['id'])
                if not isinstance(match['name'], str) or not isinstance(match['lines'], list):
                    raise ValueError()
                self._tags(match['matched_tags'])
                for line in match['lines']:
                    if (type(line['line_number']) is not int or line['line_number'] < 1
                            or not isinstance(line['text'], str)):
                        raise ValueError()
                    self._tags(line['matched_tags'])
        except (KeyError, TypeError, ValueError) as error:
            raise SyncError('Invalid hashtag search results returned by the server.') from error
        return result

    def put(self, doc_id, name, content, base_revision, request_id):
        if not self.conditional_supported:
            self.capabilities()
        result = Document.parse(self.request('PUT', 'documents/' + identifier(doc_id), {
            'name': name, 'content_base64': base64.b64encode(content).decode('ascii'),
            'base_revision': base_revision, 'request_id': identifier(request_id),
            'require_base_revision': True, 'source': 'cli',
        }))
        if (result.id != doc_id or result.sha256 != hashlib.sha256(content).hexdigest()
                or result.size != len(content) or result.name != name):
            raise SyncError("The server acknowledgement differs from the queued save; the pending save is retained.")
        return result
