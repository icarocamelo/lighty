# Copyright (c) 2026 icarocamelo. All Rights Reserved.
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v1.0 which accompanies this distribution,
# and is available at https://www.eclipse.org/legal/epl-v10.html
"""
Tiny inventory "RAG" stub.

Real implementation (not built here): this would call out to lighty's own
RESTCONF northbound API -- e.g. ``GET /rests/data/network-topology:...`` or
whatever inventory/topology module lighty exposes -- to fetch the actual
site, link and cell identifiers that exist in the managed network right now.
Those identifiers would then be used to (a) ground/validate any instance id
the operator's text names explicitly, and (b) retrieve a small, relevant
context window (e.g. a handful of link/cell records near a mentioned site)
to pass into the model prompt, the way retrieval-augmented generation feeds
context to an LLM.

For v1, this module only returns a small static example inventory so the
rule-based extractor (``extractor.py``) has something plausible to fall back
to when the operator's text does not name a concrete site/link/cell id. It
never makes a network call.
"""

from __future__ import annotations

# A few example transport links (site-a <-> site-b style network paths).
_EXAMPLE_LINKS = [
    {"id": "link-siteA-siteB", "site_a": "siteA", "site_b": "siteB"},
    {"id": "link-siteC-siteD", "site_a": "siteC", "site_b": "siteD"},
    {"id": "link-core1-core2", "site_a": "core1", "site_b": "core2"},
]

# A few example RAN cells.
_EXAMPLE_CELLS = [
    {"id": "cell-001"},
    {"id": "cell-002"},
    {"id": "cell-042"},
]

_EXAMPLE_SITES = ["siteA", "siteB", "siteC", "siteD", "core1", "core2"]


def fetch_inventory_context(hint: str | None) -> dict:
    """Return a small example inventory to ground extracted instance names.

    Args:
        hint: An optional free-form hint (e.g. the request's ``context_hint``,
            or a site/cell fragment already found in the operator's text)
            that a real implementation would use to narrow the RESTCONF
            query (e.g. "only links touching siteA"). Here it is used only
            to prefer inventory entries whose id contains the hint, when
            such an entry exists; otherwise the full static example
            inventory is returned unfiltered.

    Returns:
        A dict with "links", "cells" and "sites" example entries. The
        rule-based extractor uses this only to pick a plausible instance id
        when the operator's text does not name one explicitly -- it is never
        used to invent values that override something the text *did* say.
    """
    links = list(_EXAMPLE_LINKS)
    cells = list(_EXAMPLE_CELLS)
    sites = list(_EXAMPLE_SITES)

    if hint:
        hint_lower = hint.lower()
        matched_links = [link for link in links if hint_lower in link["id"].lower()]
        matched_cells = [cell for cell in cells if hint_lower in cell["id"].lower()]
        if matched_links:
            links = matched_links
        if matched_cells:
            cells = matched_cells

    return {"links": links, "cells": cells, "sites": sites}
