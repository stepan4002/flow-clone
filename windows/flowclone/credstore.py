"""API keys for the optional cloud AI providers, kept in Windows Credential Manager.

Entries are named FlowClone/llm/<provider> (visible and removable in Control Panel →
Credential Manager → Windows Credentials). This protects keys from other Windows users and from
a stolen, powered-off disk; it does not protect against malware running as the same user.
Keys never go into config.json or the logs.
"""
import sys

_PREFIX = "FlowClone/llm/"


def _target(provider: str) -> str:
    return _PREFIX + provider


def get_key(provider: str) -> str | None:
    if sys.platform != "win32":
        return None
    import win32cred  # deferred: pywin32, Windows only
    try:
        cred = win32cred.CredRead(_target(provider), win32cred.CRED_TYPE_GENERIC)
    except Exception:  # pywintypes.error: not found
        return None
    blob = cred.get("CredentialBlob") or b""
    if isinstance(blob, str):
        return blob or None
    try:
        return blob.decode("utf-16-le") or None
    except UnicodeDecodeError:
        return None


def set_key(provider: str, key: str) -> None:
    if sys.platform != "win32":
        raise RuntimeError("API keys can only be stored on Windows")
    key = key.strip()
    if not key:
        delete_key(provider)
        return
    import win32cred
    win32cred.CredWrite({
        "Type": win32cred.CRED_TYPE_GENERIC,
        "TargetName": _target(provider),
        "UserName": "FlowClone",
        "CredentialBlob": key,
        "Comment": "FlowClone AI provider API key",
        "Persist": win32cred.CRED_PERSIST_LOCAL_MACHINE,
    }, 0)


def delete_key(provider: str) -> None:
    if sys.platform != "win32":
        return
    import win32cred
    try:
        win32cred.CredDelete(_target(provider), win32cred.CRED_TYPE_GENERIC, 0)
    except Exception:
        pass


def has_key(provider: str) -> bool:
    return get_key(provider) is not None
