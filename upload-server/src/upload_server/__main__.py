"""`python -m upload_server` 또는 `hermes-upload-server`(pyproject의 entry point)로
기동."""

from __future__ import annotations

import logging

from aiohttp import web

from .config import Config
from .server import make_app


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    config = Config.from_env()
    app = make_app(config)
    logging.getLogger(__name__).info(
        "upload-server listening on %s:%d (inbox=%s)", config.bind_host, config.bind_port, config.inbox_dir,
    )
    web.run_app(app, host=config.bind_host, port=config.bind_port)


if __name__ == "__main__":
    main()
