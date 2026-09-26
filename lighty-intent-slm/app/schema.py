# Copyright (c) 2026 icarocamelo. All Rights Reserved.
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v1.0 which accompanies this distribution,
# and is available at https://www.eclipse.org/legal/epl-v10.html
"""
Loads the JSON Schema describing the shape of a drafted lighty intent (the
object that, once parsed from ``draft_intent_json``, must validate).

The schema is kept as a standalone file (``schema/intent.schema.json``) so it
can be reused outside this process (e.g. by the Java lighty-intent-nl-client
tests, or by a future constrained-decoding grammar builder for the real
model -- see ``extractor.translate_with_model``) without having to import
Python.
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

_SCHEMA_PATH = Path(__file__).resolve().parent.parent / "schema" / "intent.schema.json"


@lru_cache(maxsize=1)
def load_intent_schema() -> dict:
    """Return the parsed JSON Schema for a drafted intent, as a dict.

    Cached after the first call; the schema file does not change at runtime.
    """
    with _SCHEMA_PATH.open("r", encoding="utf-8") as fh:
        return json.load(fh)


INTENT_SCHEMA_PATH = _SCHEMA_PATH
