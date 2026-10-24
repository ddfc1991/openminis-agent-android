"""errors.py — MCPError + env expansion, shared by every transport.

[Mines-mcp-29] Split out of transport/http.py so the daemon and the STDIO path
no longer import httpx (and its anyio/h11/certifi/ssl tree) just to get an
exception class. Under PRoot on a phone that import alone took seconds and
counted against the CLI's daemon cold-start wait.
"""

import os
import re

# $VAR and $$VAR both expand from the process env; the UI picker emits $$VAR to
# make a reference visually explicit. The optional second `$` is consumed in the
# same match (no double-expansion), so $$VAR / $${VAR} resolve identically to
# $VAR / ${VAR}.
_ENV_RE = re.compile(r"\$\$?\{?([A-Za-z_][A-Za-z0-9_]*)\}?")


class MCPError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


def expand_env(value):
    """Replace $VAR / ${VAR} / $$VAR / $${VAR} with the environment value (empty
    if unset)."""
    if not isinstance(value, str):
        return value
    return _ENV_RE.sub(lambda m: os.environ.get(m.group(1), ""), value)
