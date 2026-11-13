try:
    # Use the Windows certificate store for TLS (fixes model downloads behind
    # corporate proxies / antivirus HTTPS scanning).
    import truststore
    truststore.inject_into_ssl()
except ImportError:
    pass

from flowclone.app import main

if __name__ == "__main__":
    main()
