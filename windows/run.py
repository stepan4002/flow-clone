import os
import sys

# FlowClone.exe is a windowed app: there is no console, so sys.stdout/sys.stderr are None.
# Libraries that print progress (e.g. huggingface_hub's tqdm bars) would crash on them.
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w", encoding="utf-8")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w", encoding="utf-8")
os.environ.setdefault("HF_HUB_DISABLE_PROGRESS_BARS", "1")
# Antivirus web shields (AVG/Avast) set SSLKEYLOGFILE to a named pipe for every program.
# Python's OpenSSL on Windows then aborts the whole process on its first HTTPS connection
# ("OPENSSL_Uplink ... no OPENSSL_Applink"). FlowClone never needs TLS key logging.
os.environ.pop("SSLKEYLOGFILE", None)

import multiprocessing  # noqa: E402

if __name__ == "__main__":
    multiprocessing.freeze_support()

    # Load ONNX Runtime before Qt: both ship the MSVC runtime (msvcp140.dll) and the copy
    # loaded first wins; onnxruntime needs the newer one (an older one crashes it).
    try:
        import onnxruntime  # noqa: F401
    except Exception:
        pass  # reported properly by the self-test / the engine loader

    if "--selftest" in sys.argv:
        try:
            from flowclone.selftest import main as selftest_main
            sys.exit(selftest_main())
        except SystemExit:
            raise
        except BaseException as e:  # never hang CI on a crash dialog
            try:
                import tempfile
                with open(os.path.join(tempfile.gettempdir(), "FlowClone-selftest.log"),
                          "a", encoding="utf-8") as f:
                    f.write(f"FATAL {type(e).__name__}: {e}\n")
            finally:
                os._exit(1)

    try:
        # Use the Windows certificate store for TLS (fixes model downloads behind
        # corporate proxies / antivirus HTTPS scanning).
        import truststore
        truststore.inject_into_ssl()
    except ImportError:
        pass

    from flowclone import crash
    from flowclone.logging_setup import LOG_DIR, setup_logging

    setup_logging()
    crash.install(LOG_DIR)
    try:
        from flowclone.app import main
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as e:
        import logging
        logging.getLogger("flowclone").critical("FlowClone could not start", exc_info=True)
        crash.show_fatal(f"FlowClone could not start:\n\n{e}\n\n"
                         f"Details are in the log:\n{LOG_DIR / 'flowclone.log'}")
        sys.exit(1)
