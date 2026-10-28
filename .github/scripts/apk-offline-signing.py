"""Offline-key APK v2 signing transport. Private key is used only by local `sign`."""
import base64
import hashlib
import json
from pathlib import Path
import struct
import sys

CERT_SHA256 = '06cc1ec2c1281e4983a93650e39d6293b818cb7c7e8f438de7b48b77e281f5a5'
ALGORITHM = 0x0103


def u32(data, at):
    return struct.unpack_from('<I', data, at)[0]


def lp(data):
    return struct.pack('<I', len(data)) + data


def field(data, at=0):
    size = u32(data, at)
    end = at + 4 + size
    assert end <= len(data)
    return data[at + 4:end], end


def sections(raw):
    eocd = raw.rfind(b'PK\x05\x06')
    assert eocd >= 0 and eocd + 22 + struct.unpack_from('<H', raw, eocd + 20)[0] == len(raw)
    central = u32(raw, eocd + 16)
    assert central + u32(raw, eocd + 12) == eocd
    assert raw[central - 16:central] == b'APK Sig Block 42'
    size = struct.unpack_from('<Q', raw, central - 24)[0]
    start = central - size - 8
    assert struct.unpack_from('<Q', raw, start)[0] == size
    trailer = bytearray(raw[eocd:])
    struct.pack_into('<I', trailer, 16, start)
    return start, central, eocd, bytes(trailer)


def content_digest(raw):
    start, central, eocd, trailer = sections(raw)
    chunks = []
    for region in (memoryview(raw)[:start], memoryview(raw)[central:eocd], trailer):
        for pos in range(0, len(region), 1024 * 1024):
            chunk = region[pos:pos + 1024 * 1024]
            h = hashlib.sha256(b'\xa5' + struct.pack('<I', len(chunk)))
            h.update(chunk)
            chunks.append(h.digest())
    return hashlib.sha256(b'\x5a' + struct.pack('<I', len(chunks)) + b''.join(chunks)).digest()


def signed_data(digest, certificate):
    return lp(lp(struct.pack('<I', ALGORITHM) + lp(digest))) + lp(lp(certificate)) + lp(b'')


def request(apk, dest):
    raw = Path(apk).read_bytes()
    start, central, _, _ = sections(raw)
    at = start + 8
    original_digest = None
    while at < central - 24:
        size = struct.unpack_from('<Q', raw, at)[0]
        tag = u32(raw, at + 8)
        value = raw[at + 12:at + 8 + size]
        at += size + 8
        if tag == 0x7109871a:
            signers, _ = field(value)
            signer, _ = field(signers)
            data, _ = field(signer)
            digests, _ = field(data)
            pos = 0
            while pos < len(digests):
                entry, pos = field(digests, pos)
                if u32(entry, 0) == ALGORITHM:
                    original_digest, _ = field(entry, 4)
    digest = content_digest(raw)
    assert original_digest == digest, 'Input APK content digest mismatch'
    value = {'sourceApkSha256': hashlib.sha256(raw).hexdigest(), 'contentDigestSha256': digest.hex()}
    Path(dest).write_text(json.dumps(value, indent=2) + '\n')
    print('APK_SIGNING_REQUEST ' + json.dumps(value))


def sign(request_file, key_file, dest):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.serialization import pkcs12
    value = json.loads(Path(request_file).read_text())
    key, certificate, _ = pkcs12.load_key_and_certificates(Path(key_file).read_bytes(), b'android')
    assert certificate.fingerprint(hashes.SHA256()).hex() == CERT_SHA256
    der = certificate.public_bytes(serialization.Encoding.DER)
    public = certificate.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    data = signed_data(bytes.fromhex(value['contentDigestSha256']), der)
    signature = key.sign(data, padding.PKCS1v15(), hashes.SHA256())
    certificate.public_key().verify(signature, data, padding.PKCS1v15(), hashes.SHA256())
    value.update(certificateDerBase64=base64.b64encode(der).decode(), publicKeyDerBase64=base64.b64encode(public).decode(), signatureBase64=base64.b64encode(signature).decode(), certificateSha256=CERT_SHA256)
    Path(dest).write_text(json.dumps(value, indent=2) + '\n')
    print('Signed APK content digest locally; private key retained locally.')


def finish(apk, response_file, dest):
    raw = Path(apk).read_bytes()
    value = json.loads(Path(response_file).read_text())
    assert hashlib.sha256(raw).hexdigest() == value['sourceApkSha256']
    digest = content_digest(raw)
    assert digest.hex() == value['contentDigestSha256']
    certificate = base64.b64decode(value['certificateDerBase64'], validate=True)
    assert hashlib.sha256(certificate).hexdigest() == CERT_SHA256 == value['certificateSha256']
    public = base64.b64decode(value['publicKeyDerBase64'], validate=True)
    signature = base64.b64decode(value['signatureBase64'], validate=True)
    signer = lp(signed_data(digest, certificate)) + lp(lp(struct.pack('<I', ALGORITHM) + lp(signature))) + lp(public)
    v2 = lp(lp(signer))
    pair = struct.pack('<Q', 4 + len(v2)) + struct.pack('<I', 0x7109871a) + v2
    size = len(pair) + 24
    block = struct.pack('<Q', size) + pair + struct.pack('<Q', size) + b'APK Sig Block 42'
    start, central, eocd, trailer = sections(raw)
    trailer = bytearray(trailer)
    struct.pack_into('<I', trailer, 16, start + len(block))
    result = raw[:start] + block + raw[central:eocd] + trailer
    assert content_digest(result) == digest
    Path(dest).write_bytes(result)
    print('APK_FINAL_SHA256 ' + hashlib.sha256(result).hexdigest())
    print('APK_FINAL_CERTIFICATE_SHA256 ' + CERT_SHA256)


if __name__ == '__main__':
    {'request': request, 'sign': sign, 'finish': finish}[sys.argv[1]](*sys.argv[2:])
